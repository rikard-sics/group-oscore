/*******************************************************************************
 * Copyright (c) 2020 Bosch IO GmbH and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v20.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *    Bosch IO GmbH - derived from org.eclipse.californium.proxy
 ******************************************************************************/

package org.eclipse.californium.proxy2;

import java.io.IOException;

import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.coap.MessageObserverAdapter;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.network.Exchange;
import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.californium.core.server.MessageDeliverer;
import org.eclipse.californium.core.server.resources.Resource;
import org.eclipse.californium.proxy2.resources.ProxyCacheResource;
import org.eclipse.californium.proxy2.resources.StatsResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The class represent the container of the resources and the layers used by the
 * proxy. A URI of an HTTP request might look like this:
 * http://localhost:8080/proxy/coap://localhost:5683/example
 */
public class ProxyHttpServer {

	private final static Logger LOGGER = LoggerFactory.getLogger(ProxyHttpServer.class);

	private final ProxyCacheResource cacheResource = new ProxyCacheResource(true);
	private final StatsResource statsResource = new StatsResource(cacheResource);

	private MessageDeliverer proxyCoapDeliverer;
	private MessageDeliverer localCoapDeliverer;
	private Http2CoapTranslator translator;
	private boolean handlerRegistered;
	private HttpStack httpStack;

	/**
	 * Instantiates a new proxy endpoint.
	 * 
	 * @param config network configuration
	 * @param httpPort the http port
	 * @throws IOException the socket exception
	 */
	public ProxyHttpServer(NetworkConfig config, int httpPort) throws IOException {
		this(new HttpStack(config, httpPort));
	}

	private ProxyHttpServer(HttpStack stack) {
		this.httpStack = stack;
		this.httpStack.setRequestDeliverer(new MessageDeliverer() {

			@Override
			public void deliverRequest(Exchange exchange) {
				ProxyHttpServer.this.handleRequest(exchange);
			}

			@Override
			public void deliverResponse(Exchange exchange, Response response) {
			}
		});
	}

	/**
	 * Start http server.
	 * 
	 * @throws IOException in case if a non-recoverable I/O error.
	 */
	public void start() throws IOException {
		if (!handlerRegistered) {
			if (proxyCoapDeliverer != null) {
				httpStack.registerProxyRequestHandler();
				httpStack.registerHttpProxyRequestHandler();
			}
			if (localCoapDeliverer != null) {
				httpStack.registerLocalRequestHandler();
			}
			if (translator == null) {
				translator = new Http2CoapTranslator();
			}
			httpStack.setHttpTranslator(translator);
			handlerRegistered = true;
		}
		httpStack.start();
	}

	/**
	 * Stop http server.
	 */
	public void stop() {
		httpStack.stop();
	}

	public void handleRequest(final Exchange exchange) {
		final Request request = exchange.getRequest();

		LOGGER.info("ProxyEndpoint handles request {}", request);

		Response response = null;
		// ignore the request if it is reset or acknowledge
		// check if the proxy-uri is defined
		if (request.getOptions().hasProxyUri()) {
			// get the response from the cache
			response = cacheResource.getResponse(request);

			LOGGER.debug("Cache returned {}", response);

			// update statistics
			statsResource.updateStatistics(request, response != null);
		}

		// check if the response is present in the cache
		if (response != null) {
			// link the retrieved response with the request to set the
			// parameters request-specific (i.e., token, id, etc)
			exchange.sendResponse(response);
			return;
		} else {

			request.addMessageObserver(new MessageObserverAdapter() {
				@Override
				public void onResponse(final Response response) {
					responseProduced(request, response);
				}
			});

			if (request.getOptions().hasProxyUri()) {
				// handle the request as usual
				if (proxyCoapDeliverer != null) {
					proxyCoapDeliverer.deliverRequest(exchange);
				} else {
					exchange.sendResponse(new Response(ResponseCode.PROXY_NOT_SUPPORTED));
				}
			} else {
				if (localCoapDeliverer != null) {
					localCoapDeliverer.deliverRequest(exchange);
				} else {
					exchange.sendResponse(new Response(ResponseCode.PROXY_NOT_SUPPORTED));
				}
			}

			/*
			 * Martin: Originally, the request was delivered to the
			 * ProxyCoAP2Coap which was at the path proxy/coapClient or to
			 * proxy/httpClient This approach replaces this implicit fuzzy
			 * connection with an explicit and dynamically changeable one.
			 */
		}
	}

	public Resource getStatistics() {
		return statsResource;
	}

	/**
	 * Set http translator for incoming http requests and outgoing http responses.
	 * 
	 * set in {@link HttpStack} on {@link #start()}.
	 * 
	 * @param translator http translator
	 */
	public void setHttpTranslator(Http2CoapTranslator translator) {
		this.translator = translator;
	}

	/**
	 * Set deliverer for forward proxy.
	 * 
	 * Register {@link HttpStack#registerProxyRequestHandler()} and
	 * {@link HttpStack#registerHttpProxyRequestHandler()} on {@link #start()}.
	 * 
	 * @param deliverer mesage deliverer for proxy-requests
	 */
	public void setProxyCoapDeliverer(MessageDeliverer deliverer) {
		this.proxyCoapDeliverer = deliverer;
	}

	/**
	 * Set deliverer for local coap resources.
	 * 
	 * Register {@link HttpStack#registerLocalRequestHandler()} on
	 * {@link #start()}.
	 * 
	 * @param deliverer mesage deliverer for local coap resources
	 */
	public void setLocalCoapDeliverer(MessageDeliverer deliverer) {
		this.localCoapDeliverer = deliverer;
	}

	protected void responseProduced(Request request, Response response) {
		// check if the proxy-uri is defined
		if (request.getOptions().hasProxyUri()) {
			LOGGER.info("Cache response {}", request.getOptions().getProxyUri());
			// insert the response in the cache
			cacheResource.cacheResponse(request, response);
		} else {
			LOGGER.info("Do not cache response");
		}
	}
}
