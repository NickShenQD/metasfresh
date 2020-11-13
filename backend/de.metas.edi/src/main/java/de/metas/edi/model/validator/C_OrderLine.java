package de.metas.edi.model.validator;

import org.adempiere.ad.modelvalidator.annotations.Interceptor;

/*
 * #%L
 * de.metas.edi
 * %%
 * Copyright (C) 2015 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */


import org.adempiere.ad.modelvalidator.annotations.ModelChange;
import org.adempiere.util.Services;
import org.compiere.model.ModelValidator;
import org.springframework.stereotype.Component;

import de.metas.edi.api.IDesadvBL;
import de.metas.edi.model.I_C_OrderLine;

@Interceptor(I_C_OrderLine.class)
@Component
public class C_OrderLine
{
	@ModelChange(timings = ModelValidator.TYPE_BEFORE_DELETE)
	public void beforeDelete(final I_C_OrderLine orderLine)
	{
		if (orderLine.getEDI_DesadvLine_ID() < 0)
		{
			return;
		}

		Services.get(IDesadvBL.class).removeOrderLineFromDesadv(orderLine);
	}
}
