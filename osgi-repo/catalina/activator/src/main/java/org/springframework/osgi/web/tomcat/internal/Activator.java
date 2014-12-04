/*
 * Copyright 2006-2008 the original author or authors.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.osgi.web.tomcat.internal;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Properties;

import javax.management.MBeanRegistration;

import org.apache.catalina.Lifecycle;
import org.apache.catalina.Server;
import org.apache.catalina.Service;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardService;
import org.apache.catalina.util.ServerInfo;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.naming.resources.DirContextURLStreamHandler;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.url.AbstractURLStreamHandlerService;
import org.osgi.service.url.URLConstants;
import org.osgi.service.url.URLStreamHandlerService;

/**
 * Simple activator for starting Apache Tomcat Catalina container inside OSGi
 * using Tomcat's XML configuration files.
 * 
 * <p/> This activator looks initially for a <code>conf/server.xml</code> file
 * falling back to <code>conf/default-server.xml</code>. This allows the
 * default configuration to be tweaked through fragments for example.
 * 
 * @author Costin Leau
 */
public class Activator implements BundleActivator {

	/** logger */
	private static final Log log = LogFactory.getLog(Activator.class);

	/** default XML configuration */
	private static final String DEFAULT_XML_CONF_LOCATION = "conf/default-server.xml";

	/** user-configurable XML configuration */
	private static final String XML_CONF_LOCATION = "conf/server.xml";

	private BundleContext bundleContext;

	private StandardService server;

	private ServiceRegistration registration, urlRegistration;

	private Thread startupThread;


	public void start(BundleContext context) throws Exception {
		this.bundleContext = context;
		// do the initialization on a different thread
		// so the activator finishes fast
		startupThread = new Thread(new Runnable() {

			public void run() {
				log.info("Starting " + ServerInfo.getServerInfo() + " ...");

				// default startup procedure
				ClassLoader cl = Activator.class.getClassLoader();
				Thread current = Thread.currentThread();
				ClassLoader old = current.getContextClassLoader();

				try {
					current.setContextClassLoader(cl);

					server = createCatalinaServer(bundleContext.getBundle());

					server.start();

					Connector[] connectors = server.findConnectors();
					for (int i = 0; i < connectors.length; i++) {
						Connector conn = connectors[i];
						log.info("Succesfully started " + ServerInfo.getServerInfo() + " @ " + conn.getDomain() + ":"
								+ conn.getPort());
					}

					// register URL service
					urlRegistration = registerTomcatJNDIUrlService();
					// publish server as an OSGi service
					registration = publishServerAsAService(server);
					log.info("Published " + ServerInfo.getServerInfo() + " as an OSGi service");
				}
				catch (Exception ex) {
					String msg = "Cannot start " + ServerInfo.getServerInfo();
					log.error(msg, ex);
					throw new RuntimeException(msg, ex);
				}
				finally {
					current.setContextClassLoader(old);
				}
			}
		}, "Tomcat Catalina Start Thread");

		startupThread.start();
	}

	public void stop(BundleContext context) throws Exception {
		// unpublish service first
		registration.unregister();
		urlRegistration.unregister();

		log.info("Unpublished  " + ServerInfo.getServerInfo() + " OSGi service");

		// default startup procedure
		ClassLoader cl = Activator.class.getClassLoader();
		Thread current = Thread.currentThread();
		ClassLoader old = current.getContextClassLoader();

		try {
			current.setContextClassLoader(cl);
			//reset CCL 
			// current.setContextClassLoader(null);
			log.info("Stopping " + ServerInfo.getServerInfo() + " ...");
			server.stop();
			log.info("Succesfully stopped " + ServerInfo.getServerInfo());
		}
		catch (Exception ex) {
			log.error("Cannot stop " + ServerInfo.getServerInfo(), ex);
			throw ex;
		}
		finally {
			current.setContextClassLoader(old);
		}
	}

	private StandardService createCatalinaServer(Bundle bundle) throws Exception {
		// first try to use the XML file
		URL xmlConfiguration = bundle.getResource(XML_CONF_LOCATION);

		if (xmlConfiguration != null) {
			log.info("Using custom XML configuration " + xmlConfiguration);
		}
		else {
			xmlConfiguration = bundle.getResource(DEFAULT_XML_CONF_LOCATION);
			if (xmlConfiguration == null)
				log.error("No XML configuration found; bailing out...");
			else
				log.info("Using default XML configuration " + xmlConfiguration);
		}

		return createServerFromXML(xmlConfiguration);
	}

	private StandardService createServerFromXML(URL xmlConfiguration) throws IOException {
		OsgiCatalina catalina = new OsgiCatalina();
		catalina.setAwait(false);
		catalina.setUseShutdownHook(false);
		catalina.setName("Catalina");
		catalina.setParentClassLoader(Thread.currentThread().getContextClassLoader());

		// copy the URL file to a local temporary file (since Catalina doesn't use URL unfortunately)
		File configTempFile = File.createTempFile("dm.catalina", ".cfg.xml");
		configTempFile.deleteOnExit();

		// copy URL to temporary file
		copyURLToFile(xmlConfiguration.openStream(), new FileOutputStream(configTempFile));
		log.debug("Copied configuration " + xmlConfiguration + " to temporary file " + configTempFile);

		catalina.setConfigFile(configTempFile.getAbsolutePath());

		catalina.load();

		Server server = catalina.getServer();

		return (StandardService) server.findServices()[0];
	}

	private void copyURLToFile(InputStream inStream, FileOutputStream outStream) {

		int bytesRead;
		byte[] buf = new byte[4096];
		try {
			while ((bytesRead = inStream.read(buf)) >= 0) {
				outStream.write(buf, 0, bytesRead);
			}
		}
		catch (IOException ex) {
			throw (RuntimeException) new IllegalStateException("Cannot copy URL to file").initCause(ex);
		}
		finally {
			try {
				inStream.close();
			}
			catch (IOException ignore) {
			}
			try {
				outStream.close();
			}
			catch (IOException ignore) {
			}
		}
	}

	private ServiceRegistration publishServerAsAService(StandardService server) {
		Properties props = new Properties();
		// put some extra properties to easily identify the service
		props.put(Constants.SERVICE_VENDOR, "Spring Dynamic Modules");
		props.put(Constants.SERVICE_DESCRIPTION, ServerInfo.getServerInfo());
		props.put(Constants.BUNDLE_VERSION, ServerInfo.getServerNumber());
		props.put(Constants.BUNDLE_NAME, bundleContext.getBundle().getSymbolicName());

		// spring-dm specific property
		props.put("org.springframework.osgi.bean.name", "tomcat-server");

		// publish just the interfaces and the major classes (server/handlerWrapper)
		String[] classes = new String[] { StandardService.class.getName(), Service.class.getName(),
			MBeanRegistration.class.getName(), Lifecycle.class.getName() };

		return bundleContext.registerService(classes, server, props);
	}

	private ServiceRegistration registerTomcatJNDIUrlService() {
		Properties properties = new Properties();
		properties.put(URLConstants.URL_HANDLER_PROTOCOL, "jndi");
		final URLStreamHandler handler = new DirContextURLStreamHandler();

		return bundleContext.registerService(URLStreamHandlerService.class.getName(),
			new AbstractURLStreamHandlerService() {

				private final static String EMPTY_STRING = "";


				public URLConnection openConnection(URL u) throws IOException {
					return new URL(u, EMPTY_STRING, handler).openConnection();
				}
			}, properties);
	}
}