package de.metas.ui.web.window.model;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.adempiere.ad.expression.api.IExpressionEvaluator.OnVariableNotFound;
import org.adempiere.ad.expression.api.ILogicExpression;
import org.adempiere.ad.expression.api.LogicExpressionResult;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.model.RecordZoomWindowFinder;
import org.adempiere.util.Check;
import org.adempiere.util.lang.IAutoCloseable;
import org.adempiere.util.lang.ITableRecordReference;
import org.adempiere.util.lang.impl.TableRecordReference;
import org.compiere.util.Evaluatee;
import org.compiere.util.Evaluatees;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.collect.ImmutableList;

import de.metas.logging.LogManager;
import de.metas.ui.web.exceptions.EntityNotFoundException;
import de.metas.ui.web.session.UserSession;
import de.metas.ui.web.window.WindowConstants;
import de.metas.ui.web.window.controller.DocumentPermissionsHelper;
import de.metas.ui.web.window.controller.Execution;
import de.metas.ui.web.window.datatypes.DocumentId;
import de.metas.ui.web.window.datatypes.DocumentPath;
import de.metas.ui.web.window.datatypes.DocumentType;
import de.metas.ui.web.window.datatypes.WindowId;
import de.metas.ui.web.window.descriptor.DocumentDescriptor;
import de.metas.ui.web.window.descriptor.DocumentEntityDescriptor;
import de.metas.ui.web.window.descriptor.factory.DocumentDescriptorFactory;
import de.metas.ui.web.window.exceptions.DocumentNotFoundException;
import de.metas.ui.web.window.exceptions.InvalidDocumentPathException;
import de.metas.ui.web.window.model.Document.CopyMode;
import groovy.transform.Immutable;
import lombok.NonNull;

/*
 * #%L
 * metasfresh-webui-api
 * %%
 * Copyright (C) 2016 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

@Component
public class DocumentCollection
{
	private static final Logger logger = LogManager.getLogger(DocumentCollection.class);

	@Autowired
	private DocumentDescriptorFactory documentDescriptorFactory;

	@Autowired
	private UserSession userSession;

	private final LoadingCache<DocumentKey, Document> rootDocuments = CacheBuilder.newBuilder()
			.removalListener(new RemovalListener<DocumentKey, Document>()
			{
				@Override
				public void onRemoval(final RemovalNotification<DocumentKey, Document> notification)
				{
					final Document document = notification.getValue();
					document.destroy();
				}
			})
			.build(new CacheLoader<DocumentKey, Document>()
			{
				@Override
				public Document load(final DocumentKey documentKey)
				{
					return retrieveRootDocumentFromRepository(documentKey);
				}

			});

	/* package */ DocumentCollection()
	{
		super();
	}

	public DocumentDescriptorFactory getDocumentDescriptorFactory()
	{
		return documentDescriptorFactory;
	}

	public final DocumentDescriptor getDocumentDescriptor(final WindowId windowId)
	{
		return documentDescriptorFactory.getDocumentDescriptor(windowId);
	}

	public final DocumentEntityDescriptor getDocumentEntityDescriptor(final WindowId windowId)
	{
		final DocumentDescriptor descriptor = documentDescriptorFactory.getDocumentDescriptor(windowId);
		return descriptor.getEntityDescriptor();
	}

	public <R> R forDocumentReadonly(final DocumentPath documentPath, final Function<Document, R> documentProcessor)
	{
		final DocumentPath rootDocumentPath = documentPath.getRootDocumentPath();

		return forRootDocumentReadonly(rootDocumentPath, rootDocument -> {
			if (documentPath.isRootDocument())
			{
				return documentProcessor.apply(rootDocument);
			}
			else if (documentPath.isSingleIncludedDocument())
			{
				final Document includedDocument = rootDocument.getIncludedDocument(documentPath.getDetailId(), documentPath.getSingleRowId());
				DocumentPermissionsHelper.assertCanView(includedDocument, UserSession.getCurrentPermissions());

				return documentProcessor.apply(includedDocument);
			}
			else
			{
				throw new InvalidDocumentPathException(documentPath);
			}
		});
	}

	public <R> R forRootDocumentReadonly(final DocumentPath documentPath, final Function<Document, R> rootDocumentProcessor)
	{
		final DocumentKey rootDocumentKey = DocumentKey.ofRootDocumentPath(documentPath.getRootDocumentPath());

		try (final IAutoCloseable readLock = rootDocuments.getUnchecked(rootDocumentKey).lockForReading())
		{
			final Document rootDocument = rootDocuments.getUnchecked(rootDocumentKey);
			DocumentPermissionsHelper.assertCanView(rootDocument, UserSession.getCurrentPermissions());

			return rootDocumentProcessor.apply(rootDocument);
		}
	}

	public <R> R forDocumentWritable(final DocumentPath documentPath, final Function<Document, R> documentProcessor)
	{
		final DocumentPath rootDocumentPath = documentPath.getRootDocumentPath();
		return forRootDocumentWritable(rootDocumentPath, rootDocument -> {

			final Document document;
			if (documentPath.isRootDocument())
			{
				document = rootDocument;
			}
			else if (documentPath.isSingleNewIncludedDocument())
			{
				document = rootDocument.createIncludedDocument(documentPath.getDetailId());
			}
			else
			{
				document = rootDocument.getIncludedDocument(documentPath.getDetailId(), documentPath.getSingleRowId());
				DocumentPermissionsHelper.assertCanEdit(rootDocument, UserSession.getCurrentPermissions());
			}

			return documentProcessor.apply(document);
		});
	}

	public <R> R forRootDocumentWritable(final DocumentPath documentPathOrNew, final Function<Document, R> rootDocumentProcessor)
	{
		final DocumentPath rootDocumentPathOrNew = documentPathOrNew.getRootDocumentPath();

		final IDocumentChangesCollector changesCollector = Execution.getCurrentDocumentChangesCollectorOrNull();
		final Document lockHolder;
		final boolean isNewRootDocument;
		final DocumentKey rootDocumentKey;
		if (rootDocumentPathOrNew.isNewDocument())
		{
			final Document newRootDocument = createRootDocument(rootDocumentPathOrNew);
			lockHolder = newRootDocument;
			rootDocumentKey = DocumentKey.ofRootDocumentPath(newRootDocument.getDocumentPath());
			isNewRootDocument = true;
		}
		else
		{
			rootDocumentKey = DocumentKey.ofRootDocumentPath(rootDocumentPathOrNew);
			lockHolder = rootDocuments.getUnchecked(rootDocumentKey);
			isNewRootDocument = false;
		}

		try (final IAutoCloseable readLock = lockHolder.lockForWriting())
		{
			final Document rootDocument;
			if (isNewRootDocument)
			{
				rootDocument = lockHolder;
			}
			else
			{
				rootDocument = rootDocuments.getUnchecked(rootDocumentKey)
						.refreshFromRepositoryIfStaled()
						.copy(CopyMode.CheckOutWritable);

				DocumentPermissionsHelper.assertCanEdit(rootDocument, UserSession.getCurrentPermissions());
			}

			//
			// Execute the actual processor
			final R result = rootDocumentProcessor.apply(rootDocument);

			//
			// Commit or remove it from cache if deleted
			if (rootDocument.isDeleted())
			{
				rootDocuments.invalidate(rootDocumentKey);
			}
			else
			{
				commitRootDocument(rootDocument, changesCollector);
			}

			// Return the result
			return result;
		}
	}

	/**
	 * Creates a new root document.
	 *
	 * @param documentPath
	 * @return new root document (writable)
	 */
	private Document createRootDocument(final DocumentPath documentPath)
	{
		if (!documentPath.isNewDocument())
		{
			throw new InvalidDocumentPathException(documentPath, "new document ID was expected");
		}

		final WindowId windowId = documentPath.getWindowId();
		final DocumentEntityDescriptor entityDescriptor = getDocumentEntityDescriptor(windowId);
		assertNewDocumentAllowed(entityDescriptor);

		final DocumentsRepository documentsRepository = entityDescriptor.getDataBinding().getDocumentsRepository();
		final Document document = documentsRepository.createNewDocument(entityDescriptor, Document.NULL);
		// NOTE: we assume document is writable
		// NOTE: we are not adding it to index. That shall be done on "commit".
		return document;
	}

	private void assertNewDocumentAllowed(final DocumentEntityDescriptor entityDescriptor)
	{
		final ILogicExpression allowExpr = entityDescriptor.getAllowCreateNewLogic();
		final LogicExpressionResult allow = allowExpr.evaluateToResult(userSession.toEvaluatee(), OnVariableNotFound.ReturnNoResult);
		if (allow.isFalse())
		{
			throw new AdempiereException("Create not allowed");
		}
	}

	/** Retrieves document from repository */
	private Document retrieveRootDocumentFromRepository(final DocumentKey documentKey)
	{
		final DocumentEntityDescriptor entityDescriptor = getDocumentEntityDescriptor(documentKey.getWindowId());

		if (documentKey.getDocumentId().isNew())
		{
			throw new InvalidDocumentPathException("documentId cannot be NEW");
		}

		final Document document = DocumentQuery.ofRecordId(entityDescriptor, documentKey.getDocumentId())
				.retriveDocumentOrNull();
		if (document == null)
		{
			throw new DocumentNotFoundException(documentKey.getDocumentPath());
		}

		return document;
	}

	public void cacheReset()
	{
		// TODO: invalidate only those which are: 1. NOW new; 2. NOT currently editing
		rootDocuments.invalidateAll();
		rootDocuments.cleanUp();
	}

	private void commitRootDocument(@NonNull final Document rootDocument, final IDocumentChangesCollector changesCollector)
	{
		Preconditions.checkState(rootDocument.isRootDocument(), "{} is not a root document", rootDocument);

		final boolean wasNew = rootDocument.isNew();

		//
		// Try saving it if possible
		rootDocument.saveIfValidAndHasChanges(changesCollector);

		//
		// Make sure all included detail (tab) statuses are up2date.
		// IMPORTANT: we have to do this after saving because some of the logics depends on if they are any new included documents or not
		rootDocument.updateIncludedDetailsStatus(changesCollector);

		//
		// Add the saved and changed document back to index
		final DocumentKey rootDocumentKey = DocumentKey.of(rootDocument);
		final Document rootDocumentReadonly = rootDocument.copy(CopyMode.CheckInReadonly);
		rootDocuments.put(rootDocumentKey, rootDocumentReadonly);

		//
		// Make sure all events were collected for the case when we just created the new document
		// FIXME: this is a workaround and in case we find out all events were collected, we just need to remove this.
		if (wasNew)
		{
			logger.debug("Checking if we collected all events for the new document");
			final Set<String> collectedFieldNames = Execution.getCurrentDocumentChangesCollector().collectFrom(rootDocument, () -> "new document, initially missed");
			if (!collectedFieldNames.isEmpty())
			{
				logger.warn("We would expect all events to be auto-magically collected but it seems that not all of them were collected!"
						+ "\n Missed (but collected now) field names were: {}" //
						+ "\n Document path: {}", collectedFieldNames, rootDocument.getDocumentPath());
			}
		}

	}

	public void delete(final DocumentPath documentPath)
	{
		if (documentPath.isRootDocument())
		{
			final DocumentEntityDescriptor entityDescriptor = documentDescriptorFactory.getDocumentEntityDescriptor(documentPath);
			assertDeleteDocumentAllowed(entityDescriptor);
		}

		final DocumentPath rootDocumentPath = documentPath.getRootDocumentPath();
		if (rootDocumentPath.isNewDocument())
		{
			throw new InvalidDocumentPathException(rootDocumentPath);
		}

		forRootDocumentWritable(rootDocumentPath, rootDocument -> {
			if (documentPath.isRootDocument())
			{
				if (!rootDocument.isNew())
				{
					rootDocument.deleteFromRepository();
				}

				rootDocument.markAsDeleted();
			}
			else if (documentPath.hasIncludedDocuments())
			{
				rootDocument.deleteIncludedDocuments(documentPath.getDetailId(), documentPath.getRowIds());
			}
			else
			{
				throw new InvalidDocumentPathException(documentPath);
			}

			return null; // nothing to return
		});
	}

	private void assertDeleteDocumentAllowed(DocumentEntityDescriptor entityDescriptor)
	{
		final Evaluatee evalCtx = Evaluatees.mapBuilder()
				.put(WindowConstants.FIELDNAME_Processed, false)
				.build()
				.andComposeWith(userSession.toEvaluatee());
		final ILogicExpression allowExpr = entityDescriptor.getAllowDeleteLogic();
		final LogicExpressionResult allow = allowExpr.evaluateToResult(evalCtx, OnVariableNotFound.ReturnNoResult);
		if (allow.isFalse())
		{
			throw new AdempiereException("Delete not allowed");
		}
	}

	public void deleteAll(final List<DocumentPath> documentPaths)
	{
		// FIXME: i think we shall refactor this method and make sure that "deleteAll" is atomic

		for (final DocumentPath documentPath : documentPaths)
		{
			delete(documentPath);
		}
	}

	public TableRecordReference getTableRecordReference(final DocumentPath documentPath)
	{
		return documentDescriptorFactory.getTableRecordReference(documentPath);
	}

	/**
	 * Retrieves document path for given table/recordId.
	 * 
	 * @param tableRecordRef
	 * @return document path; never returns null
	 */
	public DocumentPath getDocumentPath(@NonNull final ITableRecordReference tableRecordRef)
	{
		//
		// Find the root window ID
		final int zoomInto_adWindowId = RecordZoomWindowFinder.findAD_Window_ID(tableRecordRef);
		if (zoomInto_adWindowId <= 0)
		{
			throw new EntityNotFoundException("No windowId found")
					.setParameter("tableRecordRef", tableRecordRef);
		}
		final WindowId zoomIntoWindowId = WindowId.of(zoomInto_adWindowId);

		final DocumentEntityDescriptor rootEntityDescriptor = getDocumentEntityDescriptor(zoomIntoWindowId);
		final String zoomIntoTableName = tableRecordRef.getTableName();

		//
		// We are dealing with a root document
		// (i.e. root descriptor's table is matching record's table)
		if (Objects.equals(rootEntityDescriptor.getTableName(), zoomIntoTableName))
		{
			final DocumentId rootDocumentId = DocumentId.of(tableRecordRef.getRecord_ID());
			return DocumentPath.rootDocumentPath(zoomIntoWindowId, rootDocumentId);
		}
		//
		// We are dealing with an included document
		else
		{
			// Search the root descriptor for any child entity descriptor which would match record's TableName
			final List<DocumentEntityDescriptor> childEntityDescriptors = rootEntityDescriptor.getIncludedEntities().stream()
					.filter(includedEntityDescriptor -> Objects.equals(includedEntityDescriptor.getTableName(), zoomIntoTableName))
					.collect(ImmutableList.toImmutableList());
			if (childEntityDescriptors.isEmpty())
			{
				throw new EntityNotFoundException("Cannot find the detail tab to zoom into")
						.setParameter("tableRecordRef", tableRecordRef)
						.setParameter("zoomIntoWindowId", zoomIntoWindowId)
						.setParameter("rootEntityDescriptor", rootEntityDescriptor);
			}
			else if (childEntityDescriptors.size() > 1)
			{
				logger.warn("More then one child descriptors matched our root descriptor. Picking the fist one. \nRoot descriptor: {} \nChild descriptors: {}", rootEntityDescriptor, childEntityDescriptors);
			}
			//
			final DocumentEntityDescriptor childEntityDescriptor = childEntityDescriptors.get(0);

			// Find the root DocumentId
			final DocumentId rowId = DocumentId.of(tableRecordRef.getRecord_ID());
			final DocumentId rootDocumentId = DocumentQuery.ofRecordId(childEntityDescriptor, rowId)
					.retrieveParentDocumentId(rootEntityDescriptor);

			//
			return DocumentPath.includedDocumentPath(zoomIntoWindowId, rootDocumentId, childEntityDescriptor.getDetailId(), rowId);
		}
	}

	@Immutable
	private static final class DocumentKey
	{
		public static final DocumentKey of(final Document document)
		{
			final DocumentPath documentPath = document.getDocumentPath();
			return ofRootDocumentPath(documentPath);
		}

		public static final DocumentKey ofRootDocumentPath(final DocumentPath documentPath)
		{
			if (!documentPath.isRootDocument())
			{
				throw new InvalidDocumentPathException(documentPath, "shall be a root document path");
			}
			if (documentPath.isNewDocument())
			{
				throw new InvalidDocumentPathException(documentPath, "document path for creating new documents is not allowed");
			}
			return new DocumentKey(documentPath.getDocumentType(), documentPath.getDocumentTypeId(), documentPath.getDocumentId());
		}

		private final DocumentType documentType;
		private final DocumentId documentTypeId;
		private final DocumentId documentId;

		private Integer _hashcode = null;

		private DocumentKey(final DocumentType documentType, final DocumentId documentTypeId, final DocumentId documentId)
		{
			super();
			this.documentType = Preconditions.checkNotNull(documentType, "documentType");
			this.documentTypeId = Preconditions.checkNotNull(documentTypeId, "documentTypeId");
			this.documentId = Preconditions.checkNotNull(documentId, "documentId");
		}

		@Override
		public String toString()
		{
			return MoreObjects.toStringHelper(this)
					.add("type", documentType)
					.add("typeId", documentTypeId)
					.add("documentId", documentId)
					.toString();
		}

		@Override
		public int hashCode()
		{
			if (_hashcode == null)
			{
				_hashcode = Objects.hash(documentType, documentTypeId, documentId);
			}
			return _hashcode;
		}

		@Override
		public boolean equals(final Object obj)
		{
			if (this == obj)
			{
				return true;
			}
			if (!(obj instanceof DocumentKey))
			{
				return false;
			}

			final DocumentKey other = (DocumentKey)obj;
			return Objects.equals(documentType, other.documentType)
					&& Objects.equals(documentTypeId, other.documentTypeId)
					&& Objects.equals(documentId, other.documentId);
		}

		public WindowId getWindowId()
		{
			Check.assume(documentType == DocumentType.Window, "documentType shall be {} but it was {}", DocumentType.Window, documentType);
			return WindowId.of(documentTypeId);
		}

		public DocumentId getDocumentId()
		{
			return documentId;
		}

		public DocumentPath getDocumentPath()
		{
			return DocumentPath.rootDocumentPath(documentType, documentTypeId, documentId);
		}
	} // DocumentKey
}
