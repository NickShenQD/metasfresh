package de.metas.order;

import java.math.BigDecimal;
import java.util.Optional;

import org.adempiere.service.OrgId;
import org.compiere.model.I_C_Order;
import org.compiere.model.I_C_OrderLine;
import org.compiere.util.TimeUtil;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import de.metas.bpartner.BPartnerId;
import de.metas.bpartner.BPartnerLocationId;
import de.metas.bpartner.service.IBPartnerBL;
import de.metas.freighcost.FreightCost;
import de.metas.freighcost.FreightCostContext;
import de.metas.freighcost.FreightCostRule;
import de.metas.freighcost.FreightCostService;
import de.metas.lang.SOTrx;
import de.metas.location.CountryId;
import de.metas.logging.LogManager;
import de.metas.money.CurrencyId;
import de.metas.money.Money;
import de.metas.pricing.IEditablePricingContext;
import de.metas.pricing.IPricingResult;
import de.metas.pricing.PriceListId;
import de.metas.pricing.PricingSystemId;
import de.metas.pricing.service.IPricingBL;
import de.metas.product.ProductId;
import de.metas.shipping.ShipperId;
import de.metas.util.Services;
import lombok.NonNull;

/*
 * #%L
 * de.metas.business
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

@Service
public class OrderFreightCostsService
{
	private static final String MSG_NO_FREIGHT_COST_DETAIL = "freightCost.Order.noFreightCostDetail";

	private static final Logger logger = LogManager.getLogger(OrderFreightCostsService.class);
	private final IBPartnerBL bpartnerBL = Services.get(IBPartnerBL.class);
	private final IPricingBL pricingBL = Services.get(IPricingBL.class);
	private final IOrderDAO ordersRepo = Services.get(IOrderDAO.class);
	private final IOrderLineBL orderLineBL = Services.get(IOrderLineBL.class);
	private final FreightCostService freightCostService;

	public OrderFreightCostsService(
			@NonNull final FreightCostService freightCostService)
	{
		this.freightCostService = freightCostService;
	}

	public void addFreightRateLineIfNeeded(final I_C_Order order)
	{
		final BigDecimal freightAmt = order.getFreightAmt();
		if (freightAmt.signum() == 0)
		{
			return;
		}

		final FreightCostRule freightCostRule = FreightCostRule.ofCode(order.getFreightCostRule());
		final boolean isCustomFreightCost = freightCostRule.isFixPrice()
				|| FreightCostRule.FreightIncluded.equals(freightCostRule);
		if (!isCustomFreightCost)
		{
			return;
		}

		final OrderId orderId = OrderId.ofRepoId(order.getC_Order_ID());
		if (hasFreightCostLine(orderId))
		{
			return;
		}

		final FreightCostContext freightCostContext = extractFreightCostContext(order);
		final FreightCost freightCost = freightCostService.retrieveFor(freightCostContext);

		final I_C_OrderLine freightRateOrderLine = orderLineBL.createOrderLine(order);
		orderLineBL.setProductId(
				freightRateOrderLine,
				freightCost.getFreightCostProductId(),
				true);  // setUomFromProduct

		freightRateOrderLine.setQtyEntered(BigDecimal.ONE);
		freightRateOrderLine.setQtyOrdered(BigDecimal.ONE);

		freightRateOrderLine.setIsManualPrice(true);
		freightRateOrderLine.setIsPriceEditable(false);
		freightRateOrderLine.setPriceEntered(freightAmt);
		freightRateOrderLine.setPriceActual(freightAmt);

		freightRateOrderLine.setIsManualDiscount(true);
		freightRateOrderLine.setDiscount(BigDecimal.ZERO);

		ordersRepo.save(freightRateOrderLine);
	}

	public boolean hasFreightCostLine(@NonNull final OrderId orderId)
	{
		for (final I_C_OrderLine orderLine : ordersRepo.retrieveOrderLines(orderId))
		{
			final ProductId productId = ProductId.ofRepoIdOrNull(orderLine.getM_Product_ID());
			if (productId != null && freightCostService.isFreightCostProduct(productId))
			{
				return true;
			}
		}

		return false;
	}

	private FreightCostContext extractFreightCostContext(final I_C_Order order)
	{
		final BPartnerId shipToBPartnerId = BPartnerId.ofRepoIdOrNull(order.getC_BPartner_ID());

		final BPartnerLocationId shipToBPLocationId = BPartnerLocationId.ofRepoIdOrNull(shipToBPartnerId, order.getC_BPartner_Location_ID());
		final CountryId shipToCountryId = shipToBPLocationId != null
				? bpartnerBL.getBPartnerLocationCountryId(shipToBPLocationId)
				: null;

		return FreightCostContext.builder()
				.shipFromOrgId(OrgId.ofRepoId(order.getC_Order_ID()))
				.shipToBPartnerId(shipToBPartnerId)
				.shipToCountryId(shipToCountryId)
				.shipperId(ShipperId.ofRepoIdOrNull(order.getM_Shipper_ID()))
				.date(TimeUtil.asLocalDate(order.getDateOrdered()))
				.freightCostRule(FreightCostRule.ofNullableCodeOr(order.getFreightCostRule(), FreightCostRule.FreightIncluded))
				.deliveryViaRule(DeliveryViaRule.ofNullableCodeOr(order.getDeliveryViaRule(), DeliveryViaRule.Pickup))
				.build();
	}

	private void checkFreightCost(final I_C_Order order)
	{
		if (!order.isSOTrx())
		{
			logger.debug("{} is not a sales order", order);
			return;
		}

		final FreightCostContext freightCostContext = extractFreightCostContext(order);
		if (freightCostContext.getShipToBPartnerId() == null
				|| freightCostContext.getShipToCountryId() == null
				|| freightCostContext.getShipperId() == null)
		{
			logger.debug("Can't check cause freight cost info is not yet complete for {}", order);
			return;
		}

		if (freightCostService.checkIfFree(freightCostContext))
		{
			logger.debug("No freight cost for {}", order);
			return;
		}

		freightCostService.retrieveFor(freightCostContext);
	}

	public void updateFreightAmt(@NonNull final I_C_Order order)
	{
		final Money freightRate = computeFreightRate(order).orElse(null);
		order.setFreightAmt(freightRate != null ? freightRate.getAsBigDecimal() : BigDecimal.ZERO);
	}

	private Optional<Money> computeFreightRate(final I_C_Order salesOrder)
	{
		if (!salesOrder.isSOTrx())
		{
			return Optional.empty();
		}

		final FreightCostContext freightCostContext = extractFreightCostContext(salesOrder);
		if (freightCostService.checkIfFree(freightCostContext))
		{
			return Optional.empty();
		}

		final FreightCostRule freightCostRule = freightCostContext.getFreightCostRule();
		if (freightCostRule == FreightCostRule.FlatShippingFee)
		{
			final OrderId orderId = OrderId.ofRepoIdOrNull(salesOrder.getC_Order_ID());
			final Money shipmentValueAmt = orderId != null
					? computeShipmentValueAmt(orderId).orElse(null)
					: null;
			if (shipmentValueAmt == null)
			{
				return Optional.empty();
			}

			final FreightCost freightCost = freightCostService.retrieveFor(freightCostContext);
			final Money freightRate = freightCost.getFreightRate(
					freightCostContext.getShipperId(),
					freightCostContext.getShipToCountryId(),
					freightCostContext.getDate(),
					shipmentValueAmt);
			return Optional.of(freightRate);
		}
		else if (freightCostRule == FreightCostRule.FixPrice)
		{
			// get the 'freightcost' product and return its price
			final FreightCost freightCost = freightCostService.retrieveFor(freightCostContext);

			final IEditablePricingContext pricingContext = pricingBL.createInitialContext(
					freightCost.getFreightCostProductId().getRepoId(),
					freightCostContext.getShipToBPartnerId().getRepoId(),
					0,
					BigDecimal.ONE,
					SOTrx.SALES.toBoolean());
			pricingContext.setFailIfNotCalculated(true);
			pricingContext.setPricingSystemId(PricingSystemId.ofRepoIdOrNull(salesOrder.getM_PricingSystem_ID()));
			pricingContext.setPriceListId(PriceListId.ofRepoIdOrNull(salesOrder.getM_PriceList_ID()));

			final IPricingResult pricingResult = pricingBL.calculatePrice(pricingContext);
			final Money freightRate = Money.of(pricingResult.getPriceStd(), pricingResult.getCurrencyId());
			return Optional.of(freightRate);
		}
		else
		{
			logger.debug("Freigt cost is not computed because of FreightCostRule={}", freightCostRule);
			return Optional.empty();
		}
	}

	private Optional<Money> computeShipmentValueAmt(@NonNull final OrderId orderId)
	{
		Money shipmentValueAmt = null;
		for (final I_C_OrderLine orderLine : ordersRepo.retrieveOrderLines(orderId))
		{
			final BigDecimal qty = orderLine.getQtyOrdered();
			final BigDecimal priceActual = orderLine.getPriceActual();
			final CurrencyId currencyId = CurrencyId.ofRepoId(orderLine.getC_Currency_ID());

			final Money lineValueAmt = Money.of(priceActual.multiply(qty), currencyId);

			shipmentValueAmt = shipmentValueAmt != null
					? shipmentValueAmt.add(lineValueAmt)
					: lineValueAmt;
		}

		return Optional.ofNullable(shipmentValueAmt);
	}

	public boolean isFreightCostOrderLine(@NonNull final I_C_OrderLine orderLine)
	{
		final ProductId productId = ProductId.ofRepoIdOrNull(orderLine.getM_Product_ID());
		return productId != null && freightCostService.isFreightCostProduct(productId);
	}

}
