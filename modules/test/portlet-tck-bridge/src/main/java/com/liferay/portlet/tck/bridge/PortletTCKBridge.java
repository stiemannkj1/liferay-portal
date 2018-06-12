/**
 * Copyright (c) 2000-present Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.portlet.tck.bridge;

import com.liferay.petra.log4j.Log4JUtil;
import com.liferay.portal.configuration.metatype.bnd.util.ConfigurableUtil;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.module.framework.ModuleServiceLifecycle;
import com.liferay.portal.kernel.servlet.ServletContextPool;
import com.liferay.portal.kernel.util.PropsUtil;
import com.liferay.portal.kernel.util.Time;
import com.liferay.portal.kernel.util.WebKeys;
import com.liferay.portal.struts.StrutsActionRegistryUtil;
import com.liferay.portlet.tck.bridge.configuration.PortletTCKBridgeConfiguration;
import com.liferay.portlet.tck.bridge.setup.Setup;
import com.liferay.portlet.tck.bridge.struts.PortletTCKStrutsAction;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

import java.nio.charset.Charset;

import java.util.Enumeration;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletContext;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;

/**
 * @author Matthew Tambara
 * @author Vernon Singleton
 */
@Component(
	configurationPid = "com.liferay.portlet.tck.bridge.configuration.PortletTCKBridgeConfiguration",
	configurationPolicy = ConfigurationPolicy.REQUIRE, service = {}
)
public class PortletTCKBridge {

	@Activate
	@Modified
	protected void activate(ComponentContext componentContext) {
		deactivate();

		if (!_log.isInfoEnabled()) {
			Log4JUtil.setLevel("com.liferay.portlet.tck.bridge", "INFO", true);
		}

		PortletTCKBridgeConfiguration portletTCKBridgeConfiguration =
			ConfigurableUtil.createConfigurable(
				PortletTCKBridgeConfiguration.class,
				componentContext.getProperties());

		String[] servletContextNames =
			portletTCKBridgeConfiguration.servletContextNames();

		// Wait for portlet wars to deploy ...
		// They need to be available before setting up sites and pages

		for (String servletContextName : servletContextNames) {
			_waitForDeployment(
				servletContextName, System.currentTimeMillis(),
				portletTCKBridgeConfiguration.timeout() * Time.SECOND);
		}

		Setup.setupPortletTCKSite(portletTCKBridgeConfiguration);

		StrutsActionRegistryUtil.register(_PATH, new PortletTCKStrutsAction());

		FutureTask<Void> futureTask = new FutureTask<>(
			new HandshakeServerCallable(portletTCKBridgeConfiguration));

		_handshakeServerFuture = futureTask;

		String liferayHome = PropsUtil.get("liferay.home");

		File moduleDirFile = new File(liferayHome + "/osgi/modules/");

		File[] files = moduleDirFile.listFiles();

		if (files == null) {
			_log.error(
				"activate: no files in moduleDirFile.getAbsolutePath() = " +
					moduleDirFile.getAbsolutePath());
		}
		else {
			for (File file : files) {
				String fileName = file.getName();

				if (fileName.contains(
						PortletTCKBridgeConfiguration.class.getName())) {

					boolean deleted = file.delete();

					if (deleted) {
						if (_log.isInfoEnabled()) {
							_log.info(
								"activate: removed " +
									PortletTCKBridgeConfiguration.
										class.getName() + ".cfg ...");
						}
					}
				}
			}
		}

		Thread handshakeServerThread = new Thread(
			futureTask, "Handshake server thread");

		handshakeServerThread.setDaemon(true);

		handshakeServerThread.start();
	}

	@Deactivate
	protected void deactivate() {
		Future<Void> handshakeServerFuture = _handshakeServerFuture;

		if (handshakeServerFuture != null) {
			handshakeServerFuture.cancel(true);
		}

		StrutsActionRegistryUtil.unregister(_PATH);
	}

	@Reference(target = ModuleServiceLifecycle.PORTAL_INITIALIZED, unbind = "-")
	protected void setModuleServiceLifecycle(
		ModuleServiceLifecycle moduleServiceLifecycle) {
	}

	private void _waitForDeployment(
		String servletContextName, long startTime, long timeout) {

		while ((System.currentTimeMillis() - startTime) < timeout) {
			ServletContext servletContext = ServletContextPool.get(
				servletContextName);

			boolean found = false;

			if (_log.isDebugEnabled()) {
				_log.debug(
					"_waitForDeployment: servletContextName = " +
						servletContextName);
			}

			if (servletContext != null) {
				found = true;

				Enumeration<String> attributeNames =
					servletContext.getAttributeNames();

				while (attributeNames.hasMoreElements()) {
					String attributeName = attributeNames.nextElement();

					String quotedBeanPortletIds = Pattern.quote(
						"beanPortletIds");

					Pattern beanPortletIdsPattern = Pattern.compile(
						quotedBeanPortletIds, Pattern.CASE_INSENSITIVE);

					Matcher beanPortletIdsMatcher =
						beanPortletIdsPattern.matcher(attributeName);

					String quotedPlugin = Pattern.quote("plugin");

					Pattern pluginPattern = Pattern.compile(
						quotedPlugin, Pattern.CASE_INSENSITIVE);

					Matcher pluginMatcher = pluginPattern.matcher(
						attributeName);

					if (beanPortletIdsMatcher.find() || pluginMatcher.find()) {
						if (_log.isDebugEnabled()) {
							_log.debug(
								"_waitForDeployment: attributeName = " +
									attributeName);
						}
					}
				}

				Object clbeanPortletIds = servletContext.getAttribute(
					"com.liferay.beanPortletIds");
				Object beanPortletIds = servletContext.getAttribute(
					WebKeys.BEAN_PORTLET_IDS);
				Object pluginPortlets = servletContext.getAttribute(
					WebKeys.PLUGIN_PORTLETS);

				if (_log.isDebugEnabled()) {
					_log.debug(
						"_waitForDeployment: com.liferay.beanPortletIds = " +
							clbeanPortletIds);
					_log.debug(
						"_waitForDeployment: BEAN_PORTLET_IDS = " +
							beanPortletIds);
					_log.debug(
						"_waitForDeployment: PLUGIN_PORTLETS = " +
							pluginPortlets);
				}

				if (clbeanPortletIds == null) {
					if (beanPortletIds == null) {
						if (pluginPortlets == null) {
							found = false;

							if (_log.isWarnEnabled()) {
								_log.warn(
									"_waitForDeployment: waiting for " +
										servletContextName +
											" but it is not a bean portlet " +
												"or a plugin portlet.");
							}
						}
					}
				}
			}

			if (found) {
				if (_log.isInfoEnabled()) {
					_log.info(
						"_waitForDeployment: found servletContextName = " +
							servletContextName);
				}

				return;
			}
			else {
				try {
					Thread.sleep(850);
				}
				catch (InterruptedException ie) {
					_log.error(ie.getMessage());
				}
			}
		}

		_log.error("Timeout waiting for " + servletContextName);
	}

	private static final String _PATH = "/portal/tck";

	private static final Log _log = LogFactoryUtil.getLog(
		PortletTCKBridge.class);

	private volatile Future<Void> _handshakeServerFuture;

	private static class HandshakeServerCallable implements Callable<Void> {

		@Override
		public Void call() throws IOException {
			try (ServerSocket serverSocket = new ServerSocket(
					_portletTCKBridgeConfiguration.handshakeServerPort())) {

				serverSocket.setSoTimeout(250);

				while (!Thread.interrupted()) {
					try (Socket socket = serverSocket.accept();
						OutputStream outputStream = socket.getOutputStream()) {

						outputStream.write(
							"Portlet TCK Bridge is ready".getBytes(
								Charset.defaultCharset()));
					}
					catch (SocketTimeoutException ste) {
					}
				}
			}

			return null;
		}

		private HandshakeServerCallable(
			PortletTCKBridgeConfiguration portletTCKBridgeConfiguration) {

			_portletTCKBridgeConfiguration = portletTCKBridgeConfiguration;
		}

		private final PortletTCKBridgeConfiguration
			_portletTCKBridgeConfiguration;

	}

}