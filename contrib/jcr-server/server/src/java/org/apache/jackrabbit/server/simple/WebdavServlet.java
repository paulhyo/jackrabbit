/*
 * Copyright 2005 The Apache Software Foundation.
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
package org.apache.jackrabbit.server.simple;

import org.apache.jackrabbit.webdav.lock.SimpleLockManager;
import org.apache.jackrabbit.webdav.simple.ResourceFactoryImpl;
import org.apache.jackrabbit.webdav.simple.LocatorFactoryImpl;
import org.apache.jackrabbit.webdav.simple.DavSessionProviderImpl;

import javax.servlet.http.*;
import javax.servlet.*;
import java.io.*;
import java.net.URL;

import org.apache.log4j.Logger;
import org.apache.jackrabbit.server.AbstractWebdavServlet;
import org.apache.jackrabbit.webdav.*;
import org.apache.jackrabbit.webdav.lock.LockManager;
import org.apache.commons.chain.config.ConfigParser;
import org.apache.commons.chain.Catalog;
import org.apache.commons.chain.impl.CatalogFactoryBase;

/**
 * WebdavServlet provides webdav support (level 1 and 2 complient) for repository
 * resources.
 */
public class WebdavServlet extends AbstractWebdavServlet {

    /** the default logger */
    private static final Logger log = Logger.getLogger(WebdavServlet.class);

    /** init param name of the repository prefix */
    public static final String INIT_PARAM_RESOURCE_PATH_PREFIX = "resource-path-prefix";

    /** init param file of the commons chain catalog*/
    public static final String INIT_PARAM_CHAIN_CATALOG = "chain-catalog";

    /**
     * Map used to remember any webdav lock created without being reflected
     * in the underlaying repository.
     * This is needed because some clients rely on a successful locking
     * mechanism in order to perform properly (e.g. mac OSX built-in dav client)
     */
    private LockManager lockManager;

    /** the resource factory */
    private DavResourceFactory resourceFactory;

    /** the locator factory */
    private DavLocatorFactory locatorFactory;

    /** the session provider */
    private DavSessionProvider sessionProvider;

    /** the repository prefix retrieved from config */
    private static String resourcePathPrefix;

    private static Catalog chainCatalog;

    /**
     * Init this servlet
     *
     * @throws ServletException
     */
    public void init() throws ServletException {
        super.init();

	resourcePathPrefix = getInitParameter(INIT_PARAM_RESOURCE_PATH_PREFIX);
	if (resourcePathPrefix == null) {
	    log.debug("Missing path prefix > setting to empty string.");
	    resourcePathPrefix = "";
	} else if (resourcePathPrefix.endsWith("/")) {
	    log.debug("Path prefix ends with '/' > removing trailing slash.");
	    resourcePathPrefix = resourcePathPrefix.substring(0, resourcePathPrefix.length()-1);
	}
	log.info(INIT_PARAM_RESOURCE_PATH_PREFIX + " = '" + resourcePathPrefix + "'");

        try {
            String chain = getInitParameter(INIT_PARAM_CHAIN_CATALOG);
            URL chainUrl = getServletContext().getResource(chain);
            ConfigParser parser = new ConfigParser();
            parser.parse(chainUrl);
            chainCatalog = CatalogFactoryBase.getInstance().getCatalog();
        } catch (Exception e) {
            throw new ServletException(e);
        }
        log.info(INIT_PARAM_CHAIN_CATALOG + " = '" + chainCatalog + "'");
    }

    /**
     * Service the given request.
     *
     * @param request
     * @param response
     * @throws ServletException
     * @throws IOException
     */
    protected void service(HttpServletRequest request, HttpServletResponse response)
	throws ServletException, IOException {

	try {
            WebdavRequest webdavRequest = new WebdavRequestImpl(request, getLocatorFactory());
            WebdavResponse webdavResponse = new WebdavResponseImpl(response);

            // make sure there is a authenticated user
	    getSessionProvider().acquireSession(webdavRequest);
	    if (webdavRequest.getDavSession() == null) {
 		return;
 	    }

	    // check matching if=header for lock-token relevant operations
	    DavResource resource = createResource(webdavRequest.getRequestLocator(), webdavRequest, webdavResponse);
	    if (resource.exists() && !webdavRequest.matchesIfHeader(resource)) {
		webdavResponse.sendError(DavServletResponse.SC_PRECONDITION_FAILED);
		return;
	    }

	    /* set cache control headers in order to deal with non-dav complient
	     * http1.1 or http1.0 proxies. >> see RFC2518 9.4.5 */
	    webdavResponse.addHeader("Pragma", "No-cache");  // http1.0
	    webdavResponse.addHeader("Cache-Control", "no-cache"); // http1.1

	    int methodCode = DavMethods.getMethodCode(webdavRequest.getMethod());
	    switch (methodCode) {
		case DavMethods.DAV_HEAD:
		case DavMethods.DAV_GET:
		    doGet(webdavRequest, webdavResponse, resource);
                case DavMethods.DAV_OPTIONS:
                    doOptions(webdavRequest, webdavResponse, resource);
                    break;
		case DavMethods.DAV_PROPFIND:
		    doPropFind(webdavRequest, webdavResponse, resource);
		    break;
		case DavMethods.DAV_PROPPATCH:
		    doPropPatch(webdavRequest, webdavResponse, resource);
		    break;
		case DavMethods.DAV_PUT:
		case DavMethods.DAV_POST:
		    doPut(webdavRequest, webdavResponse, resource);
		    break;
		case DavMethods.DAV_DELETE:
		    doDelete(webdavRequest, webdavResponse, resource);
		    break;
		case DavMethods.DAV_COPY:
		    doCopy(webdavRequest, webdavResponse, resource);
		    break;
		case DavMethods.DAV_MOVE:
		    doMove(webdavRequest, webdavResponse, resource);
		    break;
		case DavMethods.DAV_MKCOL:
		    doMkCol(webdavRequest, webdavResponse, resource);
		    break;
		case DavMethods.DAV_LOCK:
		    doLock(webdavRequest, webdavResponse, resource);
		    break;
		case DavMethods.DAV_UNLOCK:
		    doUnlock(webdavRequest, webdavResponse, resource);
		    break;
		default:
		    // GET, HEAD, TRACE......
		    super.service(request, response);
	    }
	    getSessionProvider().releaseSession(webdavRequest);

	} catch (DavException e) {
            // special handling for unauthorized, should be done nicer
            if (e.getErrorCode() == HttpServletResponse.SC_UNAUTHORIZED) {
                response.setHeader("WWW-Authenticate", "Basic Realm=Jackrabbit");
            }
	    response.sendError(e.getErrorCode());
	}
    }

    /**
     * The MKCOL method
     *
     * @throws IOException
     */
    protected void doMkCol(WebdavRequest request, WebdavResponse response,
                           DavResource resource) throws IOException, DavException {
        // mkcol request with request.body is not supported.
        if (request.getContentLength()>0 || request.getHeader("Transfer-Encoding") != null) {
            response.sendError(DavServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
            return;
        }
        super.doMkCol(request, response, resource);
    }

    /**
     * Build a <code>DavResource</code> from the given path.<br>
     * Please note, that the resource may not have a corresponding element in
     * the repository in which case, {@link DavResource#exists()} will return
     * false.
     *
     * @see AbstractWebdavServlet#createResource(org.apache.jackrabbit.webdav.DavResourceLocator, org.apache.jackrabbit.webdav.WebdavRequest, org.apache.jackrabbit.webdav.WebdavResponse)
     */
    protected DavResource createResource(DavResourceLocator locator, WebdavRequest request, WebdavResponse response)
            throws DavException {
        return getResourceFactory().createResource(locator, request, response);
    }

    /**
     * Returns the configured path prefix
     *
     * @return resourcePathPrefix
     * @see #INIT_PARAM_RESOURCE_PATH_PREFIX
     */
    public static String getPathPrefix() {
	return resourcePathPrefix;
    }

    /**
     * Returns the <code>DavLocatorFactory</code>. If no locator factory has
     * been set or created a new instance of {@link org.apache.jackrabbit.webdav.simple.LocatorFactoryImpl} is
     * returned.
     *
     * @return the locator factory
     */
    public DavLocatorFactory getLocatorFactory() {
	if (locatorFactory == null) {
	    locatorFactory = new LocatorFactoryImpl(resourcePathPrefix);
	}
	return locatorFactory;
    }

    /**
     * Set the locator factory
     *
     * @param locatorFactory
     */
    public void setLocatorFactory(DavLocatorFactory locatorFactory) {
	this.locatorFactory = locatorFactory;
    }

    /**
     * Returns the <code>LockManager</code>. If no lock manager has
     * been set or created a new instance of {@link SimpleLockManager} is
     * returned.
     *
     * @return the lock manager
     */
    public LockManager getLockManager() {
	if (lockManager == null) {
	    lockManager = new SimpleLockManager();
	}
	return lockManager;
    }

    /**
     * Set the lock manager
     *
     * @param lockManager
     */
    public void setLockManager(LockManager lockManager) {
	this.lockManager = lockManager;
    }

    /**
     * Returns the <code>DavResourceFactory</code>. If no request factory has
     * been set or created a new instance of {@link ResourceFactoryImpl} is
     * returned.
     *
     * @return the resource factory
     */
    public DavResourceFactory getResourceFactory() {
	if (resourceFactory == null) {
	    resourceFactory = new ResourceFactoryImpl(getLockManager());
	}
	return resourceFactory;
    }

    /**
     * Set the resource factory
     *
     * @param resourceFactory
     */
    public void setResourceFactory(DavResourceFactory resourceFactory) {
	this.resourceFactory = resourceFactory;
    }

    /**
     * Returns the <code>DavSessionProvider</code>. If no session provider has
     * been set or created a new instance of {@link org.apache.jackrabbit.webdav.simple.DavSessionProviderImpl} is
     * returned.
     *
     * @return the session provider
     */
    public DavSessionProvider getSessionProvider() {
	if (sessionProvider == null) {
	    sessionProvider = new DavSessionProviderImpl();
	}
	return sessionProvider;
    }

    /**
     * Set the session provider
     *
     * @param sessionProvider
     */
    public void setSessionProvider(DavSessionProvider sessionProvider) {
	this.sessionProvider = sessionProvider;
    }
}
