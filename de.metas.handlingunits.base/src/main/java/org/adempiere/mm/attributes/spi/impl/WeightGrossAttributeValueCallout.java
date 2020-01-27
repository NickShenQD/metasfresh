package org.adempiere.mm.attributes.spi.impl;

/*
 * #%L
 * de.metas.handlingunits.base
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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

import java.math.BigDecimal;
import java.util.Properties;

import javax.annotation.Nullable;

import org.adempiere.mm.attributes.api.IAttributeSet;
import org.adempiere.mm.attributes.spi.IAttributeValueContext;
import org.compiere.model.I_M_Attribute;
import org.compiere.model.X_M_Attribute;

import de.metas.handlingunits.attribute.IWeightable;
import de.metas.handlingunits.attribute.propagation.IHUAttributePropagationContext;
import de.metas.util.Check;
import lombok.NonNull;

public class WeightGrossAttributeValueCallout extends AbstractWeightAttributeValueCallout
{
	/**
	 * Recalculates the net weight based on the current values of the given <code>attributeSet</code>'s Weight Gross and Weight Tare values.
	 *
	 * @see org.adempiere.mm.attributes.api.impl.AttributeSetCalloutExecutor#executeCallouts(IAttributeSet, I_M_Attribute, Object, Object) the implementation
	 */
	@Override
	public void onValueChanged0(final IAttributeValueContext attributeValueContext,
			final IAttributeSet attributeSet,
			final I_M_Attribute attribute,
			final Object valueOld,
			final Object valueNew)
	{
		recalculateWeightNet(attributeSet);
	}

	/**
	 * @return {@link BigDecimal#ZERO}
	 */
	@Override
	public Object generateSeedValue(final IAttributeSet attributeSet, final I_M_Attribute attribute,
			@Nullable final Object valueInitialDefault)
	{
		// we don't support a value different from null
		Check.assumeNull(valueInitialDefault, "valueInitialDefault null");

		return BigDecimal.ZERO;
	}

	@Override
	public String getAttributeValueType()
	{
		return X_M_Attribute.ATTRIBUTEVALUETYPE_Number;
	}

	@Override
	public boolean canGenerateValue(final Properties ctx, final IAttributeSet attributeSet, final I_M_Attribute attribute)
	{
		return true;
	}

	@Override
	public BigDecimal generateNumericValue(final Properties ctx, final IAttributeSet attributeSet, final I_M_Attribute attribute)
	{
		return BigDecimal.ZERO;
	}

	@Override
	protected boolean isExecuteCallout(final IAttributeValueContext attributeValueContext,
			final IAttributeSet attributeSet,
			final I_M_Attribute attribute,
			final Object valueOld,
			final Object valueNew)
	{
		final IWeightable weightable = getWeightableOrNull(attributeSet);
		if (weightable == null)
		{
			return false;
		}

		if (!weightable.isWeightGrossAttribute(attribute))
		{
			return false;
		}

		if (!weightable.hasWeightGross())
		{
			return false;
		}

		if (!weightable.hasWeightTare())
		{
			return false;
		}

		if (!(attributeValueContext instanceof IHUAttributePropagationContext))
		{
			return false;
		}

		final IHUAttributePropagationContext huAttributePropagationContext = (IHUAttributePropagationContext)attributeValueContext;
		final I_M_Attribute attr_WeightNet = weightable.getWeightNetAttribute();
		if (huAttributePropagationContext.isExternalInput()
				&& huAttributePropagationContext.isValueUpdatedBefore(attr_WeightNet))
		{
			//
			// Net weight was set externally. Do not modify it.
			return false;
		}

		return true;
	}

	@Override
	public boolean isDisplayedUI(@NonNull final IAttributeSet attributeSet, @NonNull final I_M_Attribute attribute)
	{
		return isLUorTUorTopLevelVHU(attributeSet);
	}
}
