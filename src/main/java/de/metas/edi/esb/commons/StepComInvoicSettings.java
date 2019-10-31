package de.metas.edi.esb.commons;

import org.apache.camel.CamelContext;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

/*
 * #%L
 * metasfresh-edi
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
@Builder
public class StepComInvoicSettings
{
	public static StepComInvoicSettings forReceiverGLN(
			@NonNull final CamelContext context,
			@NonNull final String recipientGLN)
	{
		return StepComInvoicSettings
				.builder()
				.partnerId(Util.resolveProperty(context, "edi.stepcom.recipientGLN." + recipientGLN + ".partnerId"))
				.testIndicator(Util.resolveProperty(context, "edi.stepcom.recipientGLN." + recipientGLN + ".invoic.testIndicator", "T"))
				.invoicLineEANCequired(Util.resolvePropertyAsBool(context, "edi.stepcom.recipientGLN." + recipientGLN + ".invoic.line.EANC.required", "false"))
				.build();
	}

	String partnerId;

	String testIndicator;

	boolean invoicLineEANCequired;
}
