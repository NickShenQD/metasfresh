package de.metas.paypalplus.processor;

import java.io.IOException;

import javax.annotation.Nullable;

import org.adempiere.exceptions.AdempiereException;
import org.springframework.stereotype.Service;

import com.braintreepayments.http.HttpRequest;
import com.braintreepayments.http.HttpResponse;
import com.braintreepayments.http.exceptions.HttpException;
import com.paypal.core.PayPalEnvironment;
import com.paypal.core.PayPalHttpClient;
import com.paypal.orders.Order;
import com.paypal.orders.OrderRequest;
import com.paypal.orders.OrdersAuthorizeRequest;
import com.paypal.orders.OrdersCreateRequest;
import com.paypal.orders.OrdersGetRequest;
import com.paypal.payments.AuthorizationsCaptureRequest;
import com.paypal.payments.Capture;
import com.paypal.payments.CaptureRequest;

import de.metas.currency.Amount;
import de.metas.paypalplus.PayPalConfig;
import de.metas.paypalplus.controller.PayPalConfigProvider;
import de.metas.paypalplus.logs.PayPalCreateLogRequest;
import de.metas.paypalplus.logs.PayPalCreateLogRequest.PayPalCreateLogRequestBuilder;
import de.metas.paypalplus.logs.PayPalLogRepository;
import de.metas.paypalplus.orders.PayPalOrderAuthorizationId;
import de.metas.paypalplus.orders.PayPalOrderId;
import lombok.NonNull;

/*
 * #%L
 * de.metas.payment.paypalplus
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
public class PayPalClient
{
	private final PayPalConfigProvider payPalConfigProvider;
	private final PayPalLogRepository logsRepo;

	public PayPalClient(
			@NonNull final PayPalConfigProvider payPalConfigProvider,
			@NonNull final PayPalLogRepository logsRepo)
	{
		this.payPalConfigProvider = payPalConfigProvider;
		this.logsRepo = logsRepo;
	}

	public Order getAPIOrderById(@NonNull final PayPalOrderId apiOrderId)
	{
		final OrdersGetRequest request = new OrdersGetRequest(apiOrderId.getAsString());
		final HttpResponse<Order> response = executeRequest(request, PayPalClientExecutionContext.EMPTY);
		return response.result();
	}

	public Order createOrder(@NonNull final OrderRequest apiRequest, @NonNull PayPalClientExecutionContext context)
	{
		final OrdersCreateRequest httpCreateRequest = new OrdersCreateRequest();
		httpCreateRequest.header("prefer", "return=representation");
		httpCreateRequest.requestBody(apiRequest);

		final HttpResponse<Order> response = executeRequest(httpCreateRequest, context);
		return response.result();
	}

	public Order authorizeOrder(
			@NonNull final PayPalOrderId apiOrderId,
			@NonNull final PayPalClientExecutionContext context)
	{
		final OrdersAuthorizeRequest request = new OrdersAuthorizeRequest(apiOrderId.getAsString());
		final HttpResponse<Order> response = executeRequest(request, context);
		return response.result();
	}

	public PayPalHttpClient createPayPalHttpClient(@NonNull final PayPalConfig config)
	{
		final PayPalEnvironment environment = createPayPalEnvironment(config);
		return new PayPalHttpClient(environment);
	}

	private static PayPalEnvironment createPayPalEnvironment(@NonNull final PayPalConfig config)
	{
		if (!config.isSandbox())
		{
			return new PayPalEnvironment(
					config.getClientId(),
					config.getClientSecret(),
					config.getBaseUrl(),
					config.getWebUrl());
		}
		else
		{
			return new PayPalEnvironment.Sandbox(
					config.getClientId(),
					config.getClientSecret());
		}
	}

	public PayPalConfig getConfig()
	{
		return payPalConfigProvider.getConfig();
	}

	private <T> HttpResponse<T> executeRequest(
			@NonNull final HttpRequest<T> request,
			@NonNull final PayPalClientExecutionContext context)
	{
		final PayPalCreateLogRequestBuilder log = PayPalCreateLogRequest.builder()
				.salesOrderId(context.getSalesOrderId())
				.paymentReservationId(context.getPaymentReservationId());

		try
		{
			log.request(request);

			final PayPalHttpClient httpClient = createPayPalHttpClient(getConfig());
			final HttpResponse<T> response = httpClient.execute(request);
			log.response(response);
			return response;
		}
		catch (final HttpException ex)
		{
			log.response(ex);
			throw AdempiereException.wrapIfNeeded(ex);
		}
		catch (final IOException ex)
		{
			log.response(ex);
			throw AdempiereException.wrapIfNeeded(ex);
		}
		finally
		{
			logsRepo.log(log.build());
		}
	}

	public Capture captureOrder(
			@NonNull final PayPalOrderAuthorizationId authId,
			@NonNull final Amount amount,
			@Nullable final Boolean finalCapture,
			@NonNull final PayPalClientExecutionContext context)
	{
		final AuthorizationsCaptureRequest request = new AuthorizationsCaptureRequest(authId.getAsString())
				.requestBody(new CaptureRequest()
						.amount(new com.paypal.payments.Money()
								.currencyCode(amount.getCurrencyCode())
								.value(amount.getAsBigDecimal().toPlainString()))
						.finalCapture(finalCapture));

		final HttpResponse<Capture> response = executeRequest(request, context);
		return response.result();
	}
}
