package de.metas.handlingunits.inventory;

import org.adempiere.service.OrgId;
import org.adempiere.warehouse.LocatorId;

import de.metas.handlingunits.HuId;
import de.metas.material.event.commons.AttributesKey;
import de.metas.product.ProductId;
import de.metas.quantity.Quantity;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

/*
 * #%L
 * de.metas.handlingunits.base
 * %%
 * Copyright (C) 2019 metas GmbH
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

@Value
public class HuForInventoryLine
{
	OrgId orgId;

	@NonNull
	HuId huId;

	@NonNull
	Quantity quantity;

	@NonNull
	ProductId productId;

	@NonNull
	AttributesKey storageAttributesKey;

	@NonNull
	LocatorId locatorId;

	@Builder
	private HuForInventoryLine(
			@NonNull final OrgId orgId,
			@NonNull final HuId huId,
			@NonNull final Quantity quantity,
			@NonNull final ProductId productId,
			@NonNull final AttributesKey storageAttributesKey,
			@NonNull final LocatorId locatorId)
	{
		this.orgId = orgId;
		this.huId = huId;
		this.quantity = quantity;

		this.productId = productId;
		this.storageAttributesKey = storageAttributesKey;
		this.locatorId = locatorId;
	}

}
