/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.spi2jcr;

import org.apache.jackrabbit.spi.RepositoryService;
import org.apache.jackrabbit.spi.IdFactory;
import org.apache.jackrabbit.spi.QValueFactory;
import org.apache.jackrabbit.spi.SessionInfo;
import org.apache.jackrabbit.spi.ItemId;
import org.apache.jackrabbit.spi.NodeId;
import org.apache.jackrabbit.spi.QNodeDefinition;
import org.apache.jackrabbit.spi.QPropertyDefinition;
import org.apache.jackrabbit.spi.PropertyId;
import org.apache.jackrabbit.spi.NodeInfo;
import org.apache.jackrabbit.spi.PropertyInfo;
import org.apache.jackrabbit.spi.Batch;
import org.apache.jackrabbit.spi.LockInfo;
import org.apache.jackrabbit.spi.IdIterator;
import org.apache.jackrabbit.spi.QueryInfo;
import org.apache.jackrabbit.spi.EventFilter;
import org.apache.jackrabbit.spi.EventBundle;
import org.apache.jackrabbit.spi.QNodeTypeDefinitionIterator;
import org.apache.jackrabbit.spi.QValue;
import org.apache.jackrabbit.name.QName;
import org.apache.jackrabbit.name.Path;
import org.apache.jackrabbit.name.PathFormat;
import org.apache.jackrabbit.name.NoPrefixDeclaredException;
import org.apache.jackrabbit.name.NameFormat;
import org.apache.jackrabbit.name.MalformedPathException;
import org.apache.jackrabbit.value.QValueFactoryImpl;
import org.apache.jackrabbit.value.ValueFormat;

import javax.jcr.RepositoryException;
import javax.jcr.Credentials;
import javax.jcr.LoginException;
import javax.jcr.NoSuchWorkspaceException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.PathNotFoundException;
import javax.jcr.ValueFormatException;
import javax.jcr.AccessDeniedException;
import javax.jcr.UnsupportedRepositoryOperationException;
import javax.jcr.ItemExistsException;
import javax.jcr.InvalidItemStateException;
import javax.jcr.ReferentialIntegrityException;
import javax.jcr.MergeException;
import javax.jcr.NamespaceException;
import javax.jcr.Repository;
import javax.jcr.Session;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.NodeIterator;
import javax.jcr.NamespaceRegistry;
import javax.jcr.Workspace;
import javax.jcr.ImportUUIDBehavior;
import javax.jcr.Value;
import javax.jcr.observation.ObservationManager;
import javax.jcr.observation.EventListener;
import javax.jcr.query.InvalidQueryException;
import javax.jcr.query.QueryManager;
import javax.jcr.query.Query;
import javax.jcr.lock.LockException;
import javax.jcr.version.VersionException;
import javax.jcr.version.VersionHistory;
import javax.jcr.version.Version;
import javax.jcr.nodetype.NoSuchNodeTypeException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NodeTypeManager;
import javax.jcr.nodetype.NodeTypeIterator;
import javax.jcr.nodetype.NodeType;
import java.util.Map;
import java.util.Iterator;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Collections;
import java.io.InputStream;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.security.AccessControlException;

/**
 * <code>RepositoryServiceImpl</code> implements a repository service on top
 * of a JCR Repository.
 */
public class RepositoryServiceImpl implements RepositoryService {

    /**
     * The JCR Repository.
     */
    private final Repository repository;

    /**
     * The id factory.
     */
    private final IdFactoryImpl idFactory = (IdFactoryImpl) IdFactoryImpl.getInstance();

    /**
     * Maps session info instances to {@link EventSubscription}s.
     */
    private final Map subscriptions = Collections.synchronizedMap(new IdentityHashMap());

    /**
     * Set to <code>true</code> if the underlying JCR repository supports
     * observation.
     */
    private final boolean supportsObservation;

    /**
     * Creates a new repository service based on the given
     * <code>repository</code>.
     *
     * @param repository a JCR repository instance.
     */
    public RepositoryServiceImpl(Repository repository) {
        this.repository = repository;
        this.supportsObservation = "true".equals(repository.getDescriptor(Repository.OPTION_OBSERVATION_SUPPORTED));
    }

    /**
     * {@inheritDoc}
     */
    public IdFactory getIdFactory() {
        return idFactory;
    }

    /**
     * {@inheritDoc}
     */
    public QValueFactory getQValueFactory() {
        return QValueFactoryImpl.getInstance();
    }

    /**
     * {@inheritDoc}
     */
    public Map getRepositoryDescriptors() throws RepositoryException {
        Map descriptors = new HashMap();
        String[] keys = repository.getDescriptorKeys();
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(Repository.OPTION_TRANSACTIONS_SUPPORTED)) {
                descriptors.put(keys[i], "false");
            } else {
                descriptors.put(keys[i], repository.getDescriptor(keys[i]));
            }
        }
        return descriptors;
    }

    /**
     * {@inheritDoc}
     */
    public SessionInfo obtain(Credentials credentials, String workspaceName)
            throws LoginException, NoSuchWorkspaceException, RepositoryException {
        Credentials duplicate = SessionInfoImpl.duplicateCredentials(credentials);
        return new SessionInfoImpl(repository.login(credentials, workspaceName), duplicate);
    }

    /**
     * {@inheritDoc}
     */
    public SessionInfo obtain(SessionInfo sessionInfo, String workspaceName)
            throws LoginException, NoSuchWorkspaceException, RepositoryException {
        SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        Session s = repository.login(sInfo.getCredentials(), workspaceName);
        return new SessionInfoImpl(s, sInfo.getCredentials());
    }

    /**
     * {@inheritDoc}
     */
    public SessionInfo impersonate(SessionInfo sessionInfo, Credentials credentials) throws LoginException, RepositoryException {
        Credentials duplicate = SessionInfoImpl.duplicateCredentials(credentials);
        SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        return new SessionInfoImpl(sInfo.getSession().impersonate(credentials), duplicate);
    }

    /**
     * {@inheritDoc}
     */
    public void dispose(SessionInfo sessionInfo) throws RepositoryException {
        subscriptions.remove(sessionInfo);
        getSessionInfoImpl(sessionInfo).getSession().logout();
    }

    /**
     * {@inheritDoc}
     */
    public String[] getWorkspaceNames(SessionInfo sessionInfo)
            throws RepositoryException {
        Session s = getSessionInfoImpl(sessionInfo).getSession();
        return s.getWorkspace().getAccessibleWorkspaceNames();
    }

    /**
     * {@inheritDoc}
     */
    public boolean isGranted(SessionInfo sessionInfo,
                             ItemId itemId,
                             String[] actions) throws RepositoryException {
        SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        String path = pathForId(itemId, sInfo);

        try {
            String actStr;
            if (actions.length == 1) {
                actStr = actions[0];
            } else {
                String comma = "";
                actStr = "";
                for (int i = 0; i < actions.length; i++) {
                    actStr += comma;
                    actStr += actions[i];
                    comma = ",";
                }
            }
            sInfo.getSession().checkPermission(path, actStr);
            return true;
        } catch (AccessControlException e) {
            return false;
        }
    }

    /**
     * {@inheritDoc}
     */
    public NodeId getRootId(SessionInfo sessionInfo)
            throws RepositoryException {
        return getIdFactory().createNodeId((String) null, Path.ROOT);
    }

    /**
     * {@inheritDoc}
     */
    public QNodeDefinition getNodeDefinition(SessionInfo sessionInfo,
                                             NodeId nodeId)
            throws RepositoryException {
        SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        return new QNodeDefinitionImpl(getNode(nodeId, sInfo).getDefinition(),
                sInfo.getNamespaceResolver());
    }

    /**
     * {@inheritDoc}
     */
    public QPropertyDefinition getPropertyDefinition(SessionInfo sessionInfo,
                                                     PropertyId propertyId)
            throws RepositoryException {
        SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        return new QPropertyDefinitionImpl(
                getProperty(propertyId, sInfo).getDefinition(),
                sInfo.getNamespaceResolver(),
                getQValueFactory());
    }

    /**
     * {@inheritDoc}
     */
    public boolean exists(SessionInfo sessionInfo, ItemId itemId)
            throws RepositoryException {
        SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        try {
            if (itemId.denotesNode()) {
                getNode((NodeId) itemId, sInfo);
            } else {
                getProperty((PropertyId) itemId, sInfo);
            }
        } catch (ItemNotFoundException e) {
            return false;
        } catch (PathNotFoundException e) {
            return false;
        }
        return true;
    }

    /**
     * {@inheritDoc}
     */
    public NodeInfo getNodeInfo(SessionInfo sessionInfo, NodeId nodeId)
            throws ItemNotFoundException, RepositoryException {
        SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        return new NodeInfoImpl(getNode(nodeId, sInfo),
                idFactory, sInfo.getNamespaceResolver());
    }

    /**
     * {@inheritDoc}
     */
    public Iterator getChildInfos(SessionInfo sessionInfo, NodeId parentId)
            throws ItemNotFoundException, RepositoryException {
        SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        NodeIterator children = getNode(parentId, sInfo).getNodes();
        List childInfos = new ArrayList();
        while (children.hasNext()) {
            childInfos.add(new ChildInfoImpl(children.nextNode(),
                    sInfo.getNamespaceResolver()));
        }
        return new IteratorHelper(childInfos);
    }

    /**
     * {@inheritDoc}
     */
    public PropertyInfo getPropertyInfo(SessionInfo sessionInfo,
                                        PropertyId propertyId)
            throws ItemNotFoundException, RepositoryException {
        SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        return new PropertyInfoImpl(getProperty(propertyId, sInfo), idFactory,
                sInfo.getNamespaceResolver(), getQValueFactory());
    }

    /**
     * {@inheritDoc}
     */
    public Batch createBatch(ItemId itemId, SessionInfo sessionInfo)
            throws RepositoryException {
        return new BatchImpl(getSessionInfoImpl(sessionInfo));
    }

    /**
     * {@inheritDoc}
     */
    public void submit(Batch batch) throws PathNotFoundException, ItemNotFoundException, NoSuchNodeTypeException, ValueFormatException, VersionException, LockException, ConstraintViolationException, AccessDeniedException, UnsupportedRepositoryOperationException, RepositoryException {
        if (batch instanceof BatchImpl) {
            ((BatchImpl) batch).end();
        } else {
            throw new RepositoryException("Unknown Batch implementation: " +
                    batch.getClass().getName());
        }
    }

    /**
     * {@inheritDoc}
     */
    public void importXml(final SessionInfo sessionInfo,
                          final NodeId parentId,
                          final InputStream xmlStream,
                          final int uuidBehaviour) throws ItemExistsException, PathNotFoundException, VersionException, ConstraintViolationException, LockException, AccessDeniedException, UnsupportedRepositoryOperationException, RepositoryException {
        final SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        executeWithLocalEvents(new Callable() {
            public Object run() throws RepositoryException {
                String path = pathForId(parentId, sInfo);
                try {
                    sInfo.getSession().getWorkspace().importXML(path, xmlStream, uuidBehaviour);
                } catch (IOException e) {
                    throw new RepositoryException(e.getMessage(), e);
                }
                return null;
            }
        }, sInfo);
    }

    /**
     * {@inheritDoc}
     */
    public void move(final SessionInfo sessionInfo,
                     final NodeId srcNodeId,
                     final NodeId destParentNodeId,
                     final QName destName) throws ItemExistsException, PathNotFoundException, VersionException, ConstraintViolationException, LockException, AccessDeniedException, UnsupportedRepositoryOperationException, RepositoryException {
        final SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        executeWithLocalEvents(new Callable() {
            public Object run() throws RepositoryException {
                String srcPath = pathForId(srcNodeId, sInfo);
                StringBuffer destPath = new StringBuffer(pathForId(destParentNodeId, sInfo));
                try {
                    if (destPath.length() > 1) {
                        destPath.append("/");
                    }
                    destPath.append(NameFormat.format(destName, sInfo.getNamespaceResolver()));
                } catch (NoPrefixDeclaredException e) {
                    throw new RepositoryException(e.getMessage(), e);
                }
                sInfo.getSession().getWorkspace().move(srcPath, destPath.toString());
                return null;
            }
        }, sInfo);
    }

    /**
     * {@inheritDoc}
     */
    public void copy(final SessionInfo sessionInfo,
                     final String srcWorkspaceName,
                     final NodeId srcNodeId,
                     final NodeId destParentNodeId,
                     final QName destName) throws NoSuchWorkspaceException, ConstraintViolationException, VersionException, AccessDeniedException, PathNotFoundException, ItemExistsException, LockException, UnsupportedRepositoryOperationException, RepositoryException {
        final SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        executeWithLocalEvents(new Callable() {
            public Object run() throws RepositoryException {
                Workspace ws = sInfo.getSession().getWorkspace();
                String destPath = getDestinationPath(destParentNodeId, destName, sInfo);
                if (ws.getName().equals(srcWorkspaceName)) {
                    // inner-workspace copy
                    String srcPath = pathForId(srcNodeId, sInfo);
                    ws.copy(srcPath, destPath);
                } else {
                    SessionInfoImpl srcInfo = getSessionInfoImpl(obtain(sInfo, srcWorkspaceName));
                    try {
                        String srcPath = pathForId(srcNodeId, srcInfo);
                        ws.copy(srcWorkspaceName, srcPath, destPath);
                    } finally {
                        dispose(srcInfo);
                    }
                }
                return null;
            }
        }, sInfo);
    }

    /**
     * {@inheritDoc}
     */
    public void update(final SessionInfo sessionInfo,
                       final NodeId nodeId,
                       final String srcWorkspaceName)
            throws NoSuchWorkspaceException, AccessDeniedException, LockException, InvalidItemStateException, RepositoryException {
        final SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        executeWithLocalEvents(new Callable() {
            public Object run() throws RepositoryException {
                getNode(nodeId, sInfo).update(srcWorkspaceName);
                return null;
            }
        }, sInfo);
    }

    /**
     * {@inheritDoc}
     */
    public void clone(final SessionInfo sessionInfo,
                      final String srcWorkspaceName,
                      final NodeId srcNodeId,
                      final NodeId destParentNodeId,
                      final QName destName,
                      final boolean removeExisting) throws NoSuchWorkspaceException, ConstraintViolationException, VersionException, AccessDeniedException, PathNotFoundException, ItemExistsException, LockException, UnsupportedRepositoryOperationException, RepositoryException {
        final SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        executeWithLocalEvents(new Callable() {
            public Object run() throws RepositoryException {
                SessionInfoImpl srcInfo = getSessionInfoImpl(obtain(sessionInfo, srcWorkspaceName));
                try {
                String srcPath = pathForId(srcNodeId, srcInfo);
                String destPath = getDestinationPath(destParentNodeId, destName, sInfo);

                Workspace wsp = sInfo.getSession().getWorkspace();
                wsp.clone(srcWorkspaceName, srcPath, destPath, removeExisting);
                } finally {
                    dispose(srcInfo);
                }
                return null;
            }
        }, sInfo);
    }

    /**
     * {@inheritDoc}
     */
    public LockInfo getLockInfo(SessionInfo sessionInfo, NodeId nodeId)
            throws LockException, RepositoryException {
        SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        return new LockInfoImpl(getNode(nodeId, sInfo), idFactory,
                sInfo.getNamespaceResolver());
    }

    /**
     * {@inheritDoc}
     */
    public LockInfo lock(final SessionInfo sessionInfo,
                         final NodeId nodeId,
                         final boolean deep,
                         final boolean sessionScoped)
            throws UnsupportedRepositoryOperationException, LockException, AccessDeniedException, InvalidItemStateException, RepositoryException {
        final SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        return (LockInfo) executeWithLocalEvents(new Callable() {
            public Object run() throws RepositoryException {
                Node n = getNode(nodeId, sInfo);
                n.lock(deep, sessionScoped);
                return new LockInfoImpl(n, idFactory, sInfo.getNamespaceResolver());
            }
        }, sInfo);
    }

    /**
     * {@inheritDoc}
     */
    public void refreshLock(SessionInfo sessionInfo, NodeId nodeId)
            throws LockException, RepositoryException {
        getNode(nodeId, getSessionInfoImpl(sessionInfo)).getLock().refresh();
    }

    /**
     * {@inheritDoc}
     */
    public void unlock(final SessionInfo sessionInfo, final NodeId nodeId)
            throws UnsupportedRepositoryOperationException, LockException, AccessDeniedException, InvalidItemStateException, RepositoryException {
        final SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        executeWithLocalEvents(new Callable() {
            public Object run() throws RepositoryException {
                getNode(nodeId, sInfo).unlock();
                return null;
            }
        }, sInfo);
    }

    /**
     * {@inheritDoc}
     */
    public void checkin(final SessionInfo sessionInfo, final NodeId nodeId)
            throws VersionException, UnsupportedRepositoryOperationException, InvalidItemStateException, LockException, RepositoryException {
        final SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        executeWithLocalEvents(new Callable() {
            public Object run() throws RepositoryException {
                getNode(nodeId, getSessionInfoImpl(sessionInfo)).checkin();
                return null;
            }
        }, sInfo);
    }

    /**
     * {@inheritDoc}
     */
    public void checkout(final SessionInfo sessionInfo, final NodeId nodeId)
            throws UnsupportedRepositoryOperationException, LockException, RepositoryException {
        final SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        executeWithLocalEvents(new Callable() {
            public Object run() throws RepositoryException {
                getNode(nodeId, getSessionInfoImpl(sessionInfo)).checkout();
                return null;
            }
        }, sInfo);
    }

    /**
     * {@inheritDoc}
     */
    public void removeVersion(final SessionInfo sessionInfo,
                              final NodeId versionHistoryId,
                              final NodeId versionId)
            throws ReferentialIntegrityException, AccessDeniedException, UnsupportedRepositoryOperationException, VersionException, RepositoryException {
        final SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        executeWithLocalEvents(new Callable() {
            public Object run() throws RepositoryException {
                Node vHistory = getNode(versionHistoryId, sInfo);
                Node version = getNode(versionId, sInfo);
                if (vHistory instanceof VersionHistory) {
                    ((VersionHistory) vHistory).removeVersion(version.getName());
                } else {
                    throw new RepositoryException("versionHistoryId does not reference a VersionHistor node");
                }
                return null;
            }
        }, sInfo);
    }

    /**
     * {@inheritDoc}
     */
    public void restore(final SessionInfo sessionInfo,
                        final NodeId nodeId,
                        final NodeId versionId,
                        final boolean removeExisting) throws VersionException, PathNotFoundException, ItemExistsException, UnsupportedRepositoryOperationException, LockException, InvalidItemStateException, RepositoryException {
        final SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        executeWithLocalEvents(new Callable() {
            public Object run() throws RepositoryException {
                Version v = (Version) getNode(versionId, sInfo);
                if (exists(sessionInfo, nodeId)) {
                    Node n = getNode(nodeId, sInfo);
                    n.restore(v, removeExisting);
                } else {
                    try {
                        // restore with rel-Path part
                        Node n = null;
                        Path relPath = null;
                        Path path = nodeId.getPath();
                        if (nodeId.getUniqueID() != null) {
                            n = getNode(idFactory.createNodeId(nodeId.getUniqueID()), sInfo);
                            relPath = (path.isAbsolute()) ? Path.ROOT.computeRelativePath(nodeId.getPath()) : path;
                        } else {
                            int degree = 0;
                            while (degree < path.getLength()) {
                                Path ancestorPath = path.getAncestor(degree);
                                NodeId parentId = idFactory.createNodeId(nodeId.getUniqueID(), ancestorPath);
                                if (exists(sessionInfo, parentId)) {
                                    n = getNode(parentId, sInfo);
                                    relPath = ancestorPath.computeRelativePath(path);
                                }
                                degree++;
                            }
                        }
                        if (n == null) {
                            throw new PathNotFoundException("Path not found " + nodeId);
                        } else {
                            n.restore(v, PathFormat.format(relPath, sInfo.getNamespaceResolver()), removeExisting);
                        }
                    } catch (MalformedPathException e) {
                        throw new RepositoryException(e);
                    } catch (NoPrefixDeclaredException e) {
                        throw new RepositoryException(e);
                    }
                }
                return null;
            }
        }, sInfo);
    }

    /**
     * {@inheritDoc}
     */
    public void restore(final SessionInfo sessionInfo,
                        final NodeId[] versionIds,
                        final boolean removeExisting) throws ItemExistsException, UnsupportedRepositoryOperationException, VersionException, LockException, InvalidItemStateException, RepositoryException {
        final SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        executeWithLocalEvents(new Callable() {
            public Object run() throws RepositoryException {
                Version[] versions = new Version[versionIds.length];
                for (int i = 0; i < versions.length; i++) {
                    Node n = getNode(versionIds[i], sInfo);
                    if (n instanceof Version) {
                        versions[i] = (Version) n;
                    } else {
                        throw new RepositoryException(n.getPath() +
                                " does not reference a Version node");
                    }
                }
                sInfo.getSession().getWorkspace().restore(versions, removeExisting);
                return null;
            }
        }, sInfo);
    }

    /**
     * {@inheritDoc}
     */
    public IdIterator merge(final SessionInfo sessionInfo,
                            final NodeId nodeId,
                            final String srcWorkspaceName,
                            final boolean bestEffort)
            throws NoSuchWorkspaceException, AccessDeniedException, MergeException, LockException, InvalidItemStateException, RepositoryException {
        final SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        return (IdIterator) executeWithLocalEvents(new Callable() {
            public Object run() throws RepositoryException {
                Node n = getNode(nodeId, sInfo);
                NodeIterator it = n.merge(srcWorkspaceName, bestEffort);
                List ids = new ArrayList();
                while (it.hasNext()) {
                    ids.add(idFactory.createNodeId(it.nextNode(),
                            sInfo.getNamespaceResolver()));
                }
                return new IteratorHelper(ids);
            }
        }, sInfo);
    }

    /**
     * {@inheritDoc}
     */
    public void resolveMergeConflict(final SessionInfo sessionInfo,
                                     final NodeId nodeId,
                                     final NodeId[] mergeFailedIds,
                                     final NodeId[] predecessorIds)
            throws VersionException, InvalidItemStateException, UnsupportedRepositoryOperationException, RepositoryException {
        final SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        executeWithLocalEvents(new Callable() {
            public Object run() throws RepositoryException {
                Node node = getNode(nodeId, sInfo);
                Version version = null;
                boolean cancel;
                try {
                    List l = Arrays.asList(mergeFailedIds);
                    Property mergeFailed = node.getProperty(NameFormat.format(QName.JCR_MERGEFAILED, sInfo.getNamespaceResolver()));
                    Value[] values = mergeFailed.getValues();
                    for (int i = 0; i < values.length; i++) {
                        String uuid = values[i].getString();
                        if (!l.contains(idFactory.createNodeId(uuid))) {
                            version = (Version) sInfo.getSession().getNodeByUUID(uuid);
                            break;
                        }
                    }

                    l =  new ArrayList(predecessorIds.length);
                    l.addAll(Arrays.asList(predecessorIds));
                    Property predecessors = node.getProperty(NameFormat.format(QName.JCR_PREDECESSORS, sInfo.getNamespaceResolver()));
                    values = predecessors.getValues();
                    for (int i = 0; i < values.length; i++) {
                        NodeId vId = idFactory.createNodeId(values[i].getString());
                        l.remove(vId);
                    }
                    cancel = l.isEmpty();
                } catch (NoPrefixDeclaredException e) {
                    throw new RepositoryException (e);
                }
                if (cancel) {
                    node.cancelMerge(version);
                } else {
                    node.doneMerge(version);
                }
                return null;
            }
        }, sInfo);
    }

    /**
     * {@inheritDoc}
     */
    public void addVersionLabel(final SessionInfo sessionInfo,
                                final NodeId versionHistoryId,
                                final NodeId versionId,
                                final QName label,
                                final boolean moveLabel) throws VersionException, RepositoryException {
        final SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        executeWithLocalEvents(new Callable() {
            public Object run() throws RepositoryException {
                String jcrLabel;
                try {
                    jcrLabel = NameFormat.format(label, sInfo.getNamespaceResolver());
                } catch (NoPrefixDeclaredException e) {
                    throw new RepositoryException(e.getMessage(), e);
                }
                Node version = getNode(versionId, sInfo);
                Node vHistory = getNode(versionHistoryId, sInfo);
                if (vHistory instanceof VersionHistory) {
                    ((VersionHistory) vHistory).addVersionLabel(
                            version.getName(), jcrLabel, moveLabel);
                } else {
                    throw new RepositoryException("versionHistoryId does not reference a VersionHistory node");
                }
                return null;
            }
        }, sInfo);
    }

    /**
     * {@inheritDoc}
     */
    public void removeVersionLabel(final SessionInfo sessionInfo,
                                   final NodeId versionHistoryId,
                                   final NodeId versionId,
                                   final QName label) throws VersionException, RepositoryException {
        final SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        executeWithLocalEvents(new Callable() {
            public Object run() throws RepositoryException {
                String jcrLabel;
                try {
                    jcrLabel = NameFormat.format(label, sInfo.getNamespaceResolver());
                } catch (NoPrefixDeclaredException e) {
                    throw new RepositoryException(e.getMessage(), e);
                }
                Node vHistory = getNode(versionHistoryId, sInfo);
                if (vHistory instanceof VersionHistory) {
                    ((VersionHistory) vHistory).removeVersionLabel(jcrLabel);
                } else {
                    throw new RepositoryException("versionHistoryId does not reference a VersionHistory node");
                }
                return null;
            }
        }, sInfo);
    }

    /**
     * {@inheritDoc}
     */
    public String[] getSupportedQueryLanguages(SessionInfo sessionInfo)
            throws RepositoryException {
        SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        return sInfo.getSession().getWorkspace().getQueryManager().getSupportedQueryLanguages();
    }

    /**
     * {@inheritDoc}
     */
    public void checkQueryStatement(SessionInfo sessionInfo,
                                    String statement,
                                    String language,
                                    Map namespaces)
            throws InvalidQueryException, RepositoryException {
        createQuery(getSessionInfoImpl(sessionInfo).getSession(), statement,
                language, namespaces);
    }

    /**
     * {@inheritDoc}
     */
    public QueryInfo executeQuery(SessionInfo sessionInfo,
                                  String statement,
                                  String language,
                                  Map namespaces) throws RepositoryException {
        SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        Query query = createQuery(sInfo.getSession(), statement,
                language, namespaces);
        return new QueryInfoImpl(query.execute(), idFactory,
                sInfo.getNamespaceResolver(), getQValueFactory());
    }

    /**
     * {@inheritDoc}
     */
    public EventFilter createEventFilter(SessionInfo sessionInfo,
                                         int eventTypes,
                                         Path absPath,
                                         boolean isDeep,
                                         String[] uuid,
                                         QName[] nodeTypeName,
                                         boolean noLocal)
            throws UnsupportedRepositoryOperationException, RepositoryException {
        // make sure there is an event subscription for this session info
        SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        if (!subscriptions.containsKey(sInfo)) {
            EventSubscription subscr = new EventSubscription(
                    idFactory, sInfo.getNamespaceResolver());
            ObservationManager obsMgr = sInfo.getSession().getWorkspace().getObservationManager();
            obsMgr.addEventListener(subscr, EventSubscription.ALL_EVENTS,
                    "/", true, null, null, true);
            subscriptions.put(sInfo, subscr);
        }

        Set ntNames = null;
        if (nodeTypeName != null) {
            ntNames = new HashSet(Arrays.asList(nodeTypeName));
        }
        return new EventFilterImpl(eventTypes, absPath, isDeep, uuid, ntNames, noLocal);
    }

    /**
     * {@inheritDoc}
     */
    public EventBundle[] getEvents(SessionInfo sessionInfo,
                                   long timeout,
                                   EventFilter[] filters)
            throws RepositoryException, UnsupportedRepositoryOperationException, InterruptedException {
        EventSubscription subscr = (EventSubscription) subscriptions.get(sessionInfo);
        if (subscr != null) {
            return subscr.getEventBundles(filters, timeout);
        } else {
            // sleep for at most one second, then return
            Thread.sleep(Math.min(timeout, 1000));
            return new EventBundle[0];
        }
    }

    /**
     * {@inheritDoc}
     */
    public Map getRegisteredNamespaces(SessionInfo sessionInfo)
            throws RepositoryException {
        SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        NamespaceRegistry nsReg = sInfo.getSession().getWorkspace().getNamespaceRegistry();
        Map namespaces = new HashMap();
        String[] prefixes = nsReg.getPrefixes();
        for (int i = 0; i < prefixes.length; i++) {
            namespaces.put(prefixes[i], nsReg.getURI(prefixes[i]));
        }
        return namespaces;
    }

    /**
     * {@inheritDoc}
     */
    public String getNamespaceURI(SessionInfo sessionInfo, String prefix)
            throws NamespaceException, RepositoryException {
        SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        return sInfo.getSession().getWorkspace().getNamespaceRegistry().getURI(prefix);
    }

    /**
     * {@inheritDoc}
     */
    public String getNamespacePrefix(SessionInfo sessionInfo, String uri)
            throws NamespaceException, RepositoryException {
        SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        return sInfo.getSession().getWorkspace().getNamespaceRegistry().getPrefix(uri);
    }

    /**
     * {@inheritDoc}
     */
    public void registerNamespace(SessionInfo sessionInfo,
                                  String prefix,
                                  String uri) throws NamespaceException, UnsupportedRepositoryOperationException, AccessDeniedException, RepositoryException {
        Session session = getSessionInfoImpl(sessionInfo).getSession();
        NamespaceRegistry nsReg = session.getWorkspace().getNamespaceRegistry();
        nsReg.registerNamespace(prefix, uri);
    }

    /**
     * {@inheritDoc}
     */
    public void unregisterNamespace(SessionInfo sessionInfo, String uri)
            throws NamespaceException, UnsupportedRepositoryOperationException, AccessDeniedException, RepositoryException {
        Session session = getSessionInfoImpl(sessionInfo).getSession();
        NamespaceRegistry nsReg = session.getWorkspace().getNamespaceRegistry();
        nsReg.unregisterNamespace(nsReg.getPrefix(uri));
    }

    /**
     * {@inheritDoc}
     */
    public QNodeTypeDefinitionIterator getNodeTypeDefinitions(
            SessionInfo sessionInfo) throws RepositoryException {
        SessionInfoImpl sInfo = getSessionInfoImpl(sessionInfo);
        NodeTypeManager ntMgr = sInfo.getSession().getWorkspace().getNodeTypeManager();
        List nodeTypes = new ArrayList();
        for (NodeTypeIterator it = ntMgr.getAllNodeTypes(); it.hasNext(); ) {
            NodeType nt = it.nextNodeType();
            nodeTypes.add(new QNodeTypeDefinitionImpl(nt,
                    sInfo.getNamespaceResolver(), getQValueFactory()));
        }
        return new IteratorHelper(nodeTypes);
    }

    //----------------------------< internal >----------------------------------

    private final class BatchImpl implements Batch {

        private final SessionInfoImpl sInfo;

        private boolean failed = false;

        BatchImpl(SessionInfoImpl sInfo) {
            this.sInfo = sInfo;
        }

        public void addNode(final NodeId parentId,
                            final QName nodeName,
                            final QName nodetypeName,
                            final String uuid) throws RepositoryException {
            executeGuarded(new Callable() {
                public Object run() throws RepositoryException {
                    Session s = sInfo.getSession();
                    Node parent = getParent(parentId, sInfo);

                    String jcrName = getJcrName(nodeName);
                    String ntName = getJcrName(nodetypeName);
                    if (uuid == null) {
                        if (ntName == null) {
                            parent.addNode(jcrName);
                        } else {
                            parent.addNode(jcrName, ntName);
                        }
                    } else {
                        String xml = createXMLFragment(jcrName, ntName, uuid);
                        InputStream in = new ByteArrayInputStream(xml.getBytes());
                        try {
                            s.importXML(parent.getPath(), in, ImportUUIDBehavior.IMPORT_UUID_COLLISION_THROW);
                        } catch (IOException e) {
                            throw new RepositoryException(e.getMessage(), e);
                        }
                    }
                    return null;
                }
            });
        }

        public void addProperty(final NodeId parentId,
                                final QName propertyName,
                                final QValue value)
                throws ValueFormatException, VersionException, LockException, ConstraintViolationException, PathNotFoundException, ItemExistsException, AccessDeniedException, UnsupportedRepositoryOperationException, RepositoryException {
            executeGuarded(new Callable() {
                public Object run() throws RepositoryException {
                    Session s = sInfo.getSession();
                    Node parent = getParent(parentId, sInfo);
                    Value jcrValue = ValueFormat.getJCRValue(value,
                            sInfo.getNamespaceResolver(), s.getValueFactory());
                    parent.setProperty(getJcrName(propertyName), jcrValue);
                    return null;
                }
            });
        }

        public void addProperty(final NodeId parentId,
                                final QName propertyName,
                                final QValue[] values) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, PathNotFoundException, ItemExistsException, AccessDeniedException, UnsupportedRepositoryOperationException, RepositoryException {
            executeGuarded(new Callable() {
                public Object run() throws RepositoryException {
                    Session s = sInfo.getSession();
                    Node n = getParent(parentId, sInfo);
                    Value[] jcrValues = new Value[values.length];
                    for (int i = 0; i < jcrValues.length; i++) {
                        jcrValues[i] = ValueFormat.getJCRValue(values[i],
                                sInfo.getNamespaceResolver(), s.getValueFactory());
                    }
                    n.setProperty(getJcrName(propertyName), jcrValues);
                    return null;
                }
            });
        }

        public void setValue(final PropertyId propertyId, final QValue value)
                throws RepositoryException {
            executeGuarded(new Callable() {
                public Object run() throws RepositoryException {
                    Session s = sInfo.getSession();
                    Value jcrValue = ValueFormat.getJCRValue(value,
                            sInfo.getNamespaceResolver(), s.getValueFactory());
                    getProperty(propertyId, sInfo).setValue(jcrValue);
                    return null;
                }
            });
        }

        public void setValue(final PropertyId propertyId, final QValue[] values)
                throws RepositoryException {
            executeGuarded(new Callable() {
                public Object run() throws RepositoryException {
                    Session s = sInfo.getSession();
                    Value[] jcrValues = new Value[values.length];
                    for (int i = 0; i < jcrValues.length; i++) {
                        jcrValues[i] = ValueFormat.getJCRValue(values[i],
                                sInfo.getNamespaceResolver(), s.getValueFactory());
                    }
                    getProperty(propertyId, sInfo).setValue(jcrValues);
                    return null;
                }
            });
        }

        public void remove(final ItemId itemId) throws RepositoryException {
            executeGuarded(new Callable() {
                public Object run() throws RepositoryException {
                    try {
                        if (itemId.denotesNode()) {
                            getNode((NodeId) itemId, sInfo).remove();
                        } else {
                            getProperty((PropertyId) itemId, sInfo).remove();
                        }
                    } catch (ItemNotFoundException e) {
                        // item was present in jcr2spi but got removed on the
                        // persistent layer in the mean time.
                        throw new InvalidItemStateException(e);
                    } catch (PathNotFoundException e) {
                        // item was present in jcr2spi but got removed on the
                        // persistent layer in the mean time.
                        throw new InvalidItemStateException(e);
                    }
                    return null;
                }
            });
        }

        public void reorderNodes(final NodeId parentId,
                                 final NodeId srcNodeId,
                                 final NodeId beforeNodeId)
                throws RepositoryException {
            executeGuarded(new Callable() {
                public Object run() throws RepositoryException {
                    Node parent = getParent(parentId, sInfo);
                    Node srcNode = getNode(srcNodeId, sInfo);
                    Node beforeNode = null;
                    if (beforeNodeId != null) {
                        beforeNode = getNode(beforeNodeId, sInfo);
                    }
                    String srcPath = srcNode.getName();
                    if (srcNode.getIndex() > 1) {
                        srcPath += "[" + srcNode.getIndex() + "]";
                    }
                    String beforePath = null;
                    if (beforeNode != null) {
                        beforePath = beforeNode.getName();
                        if (beforeNode.getIndex() > 1) {
                            beforePath += "[" + beforeNode.getIndex() + "]";
                        }
                    }
                    parent.orderBefore(srcPath, beforePath);
                    return null;
                }
            });
        }

        public void setMixins(final NodeId nodeId,
                              final QName[] mixinNodeTypeIds)
                throws RepositoryException {
            executeGuarded(new Callable() {
                public Object run() throws RepositoryException {
                    Set mixinNames = new HashSet();
                    for (int i = 0; i < mixinNodeTypeIds.length; i++) {
                        mixinNames.add(getJcrName(mixinNodeTypeIds[i]));
                    }
                    Node n = getNode(nodeId, sInfo);
                    NodeType[] nts = n.getMixinNodeTypes();
                    Set currentMixins = new HashSet();
                    for (int i = 0; i < nts.length; i++) {
                        currentMixins.add(nts[i].getName());
                    }
                    Set remove = new HashSet(currentMixins);
                    remove.removeAll(mixinNames);
                    mixinNames.removeAll(currentMixins);
                    for (Iterator it = remove.iterator(); it.hasNext(); ) {
                        n.removeMixin((String) it.next());
                    }
                    for (Iterator it = mixinNames.iterator(); it.hasNext(); ) {
                        n.addMixin((String) it.next());
                    }
                    return null;
                }
            });
        }

        public void move(final NodeId srcNodeId,
                         final NodeId destParentNodeId,
                         final QName destName) throws RepositoryException {
            executeGuarded(new Callable() {
                public Object run() throws RepositoryException {
                    String srcPath = pathForId(srcNodeId, sInfo);
                    String destPath = pathForId(destParentNodeId, sInfo);
                    if (destPath.length() > 1) {
                        destPath += "/";
                    }
                    destPath += getJcrName(destName);
                    sInfo.getSession().move(srcPath, destPath);
                    return null;
                }
            });
        }

        private void executeGuarded(Callable call) throws RepositoryException {
            if (failed) {
                return;
            }
            try {
                call.run();
            } catch (RepositoryException e) {
                failed = true;
                sInfo.getSession().refresh(false);
                throw e;
            } catch (RuntimeException e) {
                failed = true;
                sInfo.getSession().refresh(false);
                throw e;
            }
        }

        private String getJcrName(QName name) throws RepositoryException {
            if (name == null) {
                return null;
            }
            try {
                return NameFormat.format(name, sInfo.getNamespaceResolver());
            } catch (NoPrefixDeclaredException e) {
                throw new RepositoryException(e.getMessage(), e);
            }
        }

        private String createXMLFragment(String nodeName, String ntName, String uuid) {
            StringBuffer xml = new StringBuffer("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            xml.append("<sv:node xmlns:jcr=\"http://www.jcp.org/jcr/1.0\" ");
            xml.append("xmlns:sv=\"http://www.jcp.org/jcr/sv/1.0\" ");
            xml.append("sv:name=\"").append(nodeName).append("\">");
            // jcr:primaryType
            xml.append("<sv:property sv:name=\"jcr:primaryType\" sv:type=\"Name\">");
            xml.append("<sv:value>").append(ntName).append("</sv:value>");
            xml.append("</sv:property>");
            // jcr:uuid
            xml.append("<sv:property sv:name=\"jcr:uuid\" sv:type=\"String\">");
            xml.append("<sv:value>").append(uuid).append("</sv:value>");
            xml.append("</sv:property>");
            xml.append("</sv:node>");
            return xml.toString();
        }

        private void end() throws AccessDeniedException, ItemExistsException,
                ConstraintViolationException, InvalidItemStateException,
                VersionException, LockException, NoSuchNodeTypeException,
                RepositoryException {
            executeGuarded(new Callable() {
                public Object run() throws RepositoryException {
                    executeWithLocalEvents(new Callable() {
                        public Object run() throws RepositoryException {
                            sInfo.getSession().save();
                            return null;
                        }
                    }, sInfo);
                    return null;
                }
            });
        }
    }

    private interface Callable {
        public Object run() throws RepositoryException;
    }

    private SessionInfoImpl getSessionInfoImpl(SessionInfo sessionInfo)
            throws RepositoryException {
        if (sessionInfo instanceof SessionInfoImpl) {
            return (SessionInfoImpl) sessionInfo;
        } else {
            throw new RepositoryException("Unknown SessionInfo implementation: "
                    + sessionInfo.getClass().getName());
        }
    }

    private String getDestinationPath(NodeId destParentNodeId, QName destName, SessionInfoImpl sessionInfo) throws RepositoryException {
        StringBuffer destPath = new StringBuffer(pathForId(destParentNodeId, sessionInfo));
        try {
            if (destPath.length() > 1) {
                destPath.append("/");
            }
            destPath.append(NameFormat.format(destName, sessionInfo.getNamespaceResolver()));
        } catch (NoPrefixDeclaredException e) {
            throw new RepositoryException(e.getMessage(), e);
        }
        return destPath.toString();
    }

    private String pathForId(ItemId id, SessionInfoImpl sessionInfo)
            throws RepositoryException {
        Session session = sessionInfo.getSession();
        StringBuffer path = new StringBuffer();
        if (id.getUniqueID() != null) {
            path.append(session.getNodeByUUID(id.getUniqueID()).getPath());
        } else {
            path.append("/");
        }

        if (id.getPath() == null) {
            // we're done
            return path.toString();
        }

        try {
            if (id.getPath().isAbsolute()) {
                if (path.length() == 1) {
                    // root path ends with slash
                    path.setLength(0);
                }
            } else {
                // path is relative
                if (path.length() > 1) {
                    path.append("/");
                }
            }
            path.append(PathFormat.format(id.getPath(),
                    sessionInfo.getNamespaceResolver()));
        } catch (NoPrefixDeclaredException e) {
            throw new RepositoryException(e.getMessage());
        }
        return path.toString();
    }

    private Node getParent(NodeId parentId, SessionInfoImpl sessionInfo) throws InvalidItemStateException, RepositoryException {
        try {
            return getNode(parentId, sessionInfo);
        } catch (PathNotFoundException e) {
            // if the parent of an batch operation is not available, this indicates
            // that it has been destroyed by another session.
            throw new InvalidItemStateException(e);
        }
    }

    private Node getNode(NodeId id, SessionInfoImpl sessionInfo) throws ItemNotFoundException, PathNotFoundException, RepositoryException {
        Session session = sessionInfo.getSession();
        Node n;
        if (id.getUniqueID() != null) {
            n = session.getNodeByUUID(id.getUniqueID());
        } else {
            n = session.getRootNode();
        }
        Path path = id.getPath();
        if (path == null || path.denotesRoot()) {
            return n;
        }
        String jcrPath;
        try {
            jcrPath = PathFormat.format(path, sessionInfo.getNamespaceResolver());
        } catch (NoPrefixDeclaredException e) {
            throw new RepositoryException(e.getMessage(), e);
        }
        if (path.isAbsolute()) {
            jcrPath = jcrPath.substring(1, jcrPath.length());
        }
        return n.getNode(jcrPath);
    }

    private Property getProperty(PropertyId id, SessionInfoImpl sessionInfo) throws ItemNotFoundException, PathNotFoundException, RepositoryException {
        Session session = sessionInfo.getSession();
        Node n;
        if (id.getUniqueID() != null) {
            n = session.getNodeByUUID(id.getUniqueID());
        } else {
            n = session.getRootNode();
        }
        Path path = id.getPath();
        String jcrPath;
        try {
            jcrPath = PathFormat.format(path, sessionInfo.getNamespaceResolver());
        } catch (NoPrefixDeclaredException e) {
            throw new RepositoryException(e.getMessage(), e);
        }
        if (path.isAbsolute()) {
            jcrPath = jcrPath.substring(1, jcrPath.length());
        }
        return n.getProperty(jcrPath);
    }

    private Query createQuery(Session session,
                              String statement,
                              String language,
                              Map namespaces)
            throws InvalidQueryException, RepositoryException {
        NamespaceRegistry nsReg = session.getWorkspace().getNamespaceRegistry();
        QueryManager qMgr = session.getWorkspace().getQueryManager();
        try {
            // apply namespace mappings to session
            for (Iterator it = namespaces.keySet().iterator(); it.hasNext(); ) {
                String prefix = (String) it.next();
                String uri = (String) namespaces.get(prefix);
                session.setNamespacePrefix(prefix, uri);
            }
            return qMgr.createQuery(statement, language);
        } finally {
            // reset namespace mappings
            for (Iterator it = namespaces.values().iterator(); it.hasNext(); ) {
                String uri = (String) it.next();
                session.setNamespacePrefix(nsReg.getPrefix(uri), uri);
            }
        }
    }

    private Object executeWithLocalEvents(Callable call, SessionInfoImpl sInfo)
            throws RepositoryException {
        if (supportsObservation) {
            // register local event listener
            EventSubscription subscr = (EventSubscription) subscriptions.get(sInfo);
            if (subscr != null) {
                ObservationManager obsMgr = sInfo.getSession().getWorkspace().getObservationManager();
                EventListener listener = subscr.getLocalEventListener();
                obsMgr.addEventListener(listener, EventSubscription.ALL_EVENTS,
                        "/", true, null, null, false);
                try {
                    return call.run();
                } finally {
                    obsMgr.removeEventListener(listener);
                }
            }
        }
        // if we get here simply run as is
        return call.run();
    }
}
