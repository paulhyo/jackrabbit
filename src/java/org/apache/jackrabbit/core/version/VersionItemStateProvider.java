/*
 * Copyright 2004-2005 The Apache Software Foundation or its licensors,
 *                     as applicable.
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
package org.apache.jackrabbit.core.version;

import org.apache.commons.collections.ReferenceMap;
import org.apache.jackrabbit.core.*;
import org.apache.jackrabbit.core.nodetype.*;
import org.apache.jackrabbit.core.state.*;
import org.apache.jackrabbit.core.util.uuid.UUID;
import org.apache.jackrabbit.core.virtual.VirtualItemStateProvider;
import org.apache.jackrabbit.core.virtual.VirtualNodeState;
import org.apache.jackrabbit.core.virtual.VirtualPropertyState;
import org.apache.log4j.Logger;

import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import java.util.HashSet;
import java.util.Map;

/**
 * This Class implements a virtual item state provider, in order to expose the
 * versions to the version storage.
 */
public class VersionItemStateProvider implements VirtualItemStateProvider, Constants {
    /**
     * the default logger
     */
    private static Logger log = Logger.getLogger(VersionItemStateProvider.class);
    /**
     * the root node
     */
    private HistoryRootNodeState root;
    /**
     * the version manager
     */
    private final VersionManager vMgr;
    /**
     * the node type manager
     */
    private final NodeTypeRegistry ntReg;

    /**
     * the cache node states. key=ItemId, value=ItemState
     */
    private Map nodes = new ReferenceMap(ReferenceMap.HARD, ReferenceMap.SOFT);
    /**
     * node def id for a version node state
     */
    private NodeDefId NDEF_VERSION;
    /**
     * node def id for a version history node state
     */
    private NodeDefId NDEF_VERSION_HISTORY;
    /**
     * node def id for a version history root node state
     */
    private NodeDefId NDEF_VERSION_HISTORY_ROOT;

    /**
     * node def id for a version labels node state
     */
    private NodeDefId NDEF_VERSION_LABELS;

    /** the parent id */
    private final String parentId;

    /** the root node id */
    private final String rootNodeId;

    /**
     * creates a new version item state provide
     *
     * @param vMgr
     * @param rootId
     * @param parentId
     * @throws RepositoryException
     */
    public VersionItemStateProvider(VersionManager vMgr, NodeTypeRegistry ntReg, String rootId, String parentId) throws RepositoryException {
        this.vMgr = vMgr;
        this.ntReg = ntReg;
        this.rootNodeId = rootId;
        this.parentId = parentId;
        NDEF_VERSION = new NodeDefId(ntReg.getEffectiveNodeType(NT_VERSIONHISTORY).getApplicableChildNodeDef(JCR_ROOTVERSION, NT_VERSION));
        NDEF_VERSION_HISTORY = new NodeDefId(ntReg.getEffectiveNodeType(NT_UNSTRUCTURED).getApplicableChildNodeDef(JCR_ROOTVERSION, NT_VERSIONHISTORY));
        NDEF_VERSION_HISTORY_ROOT = new NodeDefId(ntReg.getEffectiveNodeType(REP_SYSTEM).getApplicableChildNodeDef(JCR_VERSIONSTORAGE, REP_VERSIONSTORAGE));
        NDEF_VERSION_LABELS = new NodeDefId(ntReg.getEffectiveNodeType(NT_VERSIONHISTORY).getApplicableChildNodeDef(JCR_VERSIONLABELS, NT_VERSIONLABELS));

        createRootNodeState();
    }

    /**
     * Creates a new root node state
     * @throws RepositoryException
     */
    private void createRootNodeState() throws RepositoryException {
        root = new HistoryRootNodeState(this, vMgr, parentId, rootNodeId);
        root.setDefinitionId(NDEF_VERSION_HISTORY_ROOT);
    }

    //-----------------------------------------------------< ItemStateManager >
    /**
     * @see ItemStateManager#hasItemState(org.apache.jackrabbit.core.ItemId)
     */
    public boolean hasItemState(ItemId id) {
        if (id instanceof NodeId) {
            return hasNodeState((NodeId) id);
        } else {
            return hasPropertyState((PropertyId) id);
        }
    }

    /**
     * @see ItemStateManager#getItemState(ItemId)
     */
    public ItemState getItemState(ItemId id)
            throws NoSuchItemStateException, ItemStateException {

        if (id instanceof NodeId) {
            return getNodeState((NodeId) id);
        } else {
            return getPropertyState((PropertyId) id);
        }
    }

    /**
     * @see ItemStateManager#getNodeReferences(NodeReferencesId)
     */
    public NodeReferences getNodeReferences(NodeReferencesId id)
            throws NoSuchItemStateException, ItemStateException {
        try {
            InternalVersionItem vi = vMgr.getItem(id.getUUID());
            if (vi != null) {
                // todo: add caching
                NodeReferences ref = new NodeReferences(id);
                ref.addAllReferences(vMgr.getItemReferences(vi));
                // check for versionstorage internal references
                if (vi instanceof InternalVersion) {
                    InternalVersion v = (InternalVersion) vi;
                    InternalVersion[] suc = v.getSuccessors();
                    for (int i=0; i<suc.length; i++) {
                        InternalVersion s = suc[i];
                        ref.addReference(new PropertyId(s.getId(), JCR_PREDECESSORS));
                    }
                    InternalVersion[] pred = v.getPredecessors();
                    for (int i=0; i<pred.length; i++) {
                        InternalVersion p = pred[i];
                        ref.addReference(new PropertyId(p.getId(), JCR_SUCCESSORS));
                    }
                }

                return ref;
            }
        } catch (RepositoryException e) {
            // ignore
        }
        throw new NoSuchItemStateException(id.getUUID());
    }

    //-------------------------------------------< VirtualItemStateProvider >---
    /**
     * @see VirtualItemStateProvider#isVirtualRoot(ItemId)
     */
    public boolean isVirtualRoot(ItemId id) {
        return id.equals(root.getId());
    }

    /**
     * @see VirtualItemStateProvider#getVirtualRootId()
     */
    public NodeId getVirtualRootId() {
        return (NodeId) root.getId();
    }

    /**
     * @see VirtualItemStateProvider#hasNodeState(NodeId)
     */
    public boolean hasNodeState(NodeId id) {
        if (nodes.containsKey(id)) {
            return true;
        } else if (id.equals(root.getId())) {
            return true;
        } else {
            return vMgr.hasItem(id.getUUID());
        }
    }

    /**
     * @see VirtualItemStateProvider#getNodeState(NodeId)
     */
    public VirtualNodeState getNodeState(NodeId id)
            throws NoSuchItemStateException, ItemStateException {

        // check if root
        if (id.equals(root.getId())) {
            return root;
        }

        // check cache
        VirtualNodeState state = (VirtualNodeState) nodes.get(id);
        if (state == null) {
            try {
                InternalVersionItem vi = vMgr.getItem(id.getUUID());
                if (vi instanceof InternalVersionHistory) {
                    state = new VersionHistoryNodeState(this, (InternalVersionHistory) vi, root.getUUID());
                    state.setDefinitionId(NDEF_VERSION_HISTORY);
                    // add version labels node state
                    String uuid = UUID.randomUUID().toString();
                    VersionLabelsNodeState vlns = new VersionLabelsNodeState(this, (InternalVersionHistory) vi, state.getUUID(), uuid);
                    vlns.setDefinitionId(NDEF_VERSION_LABELS);
                    state.addChildNodeEntry(JCR_VERSIONLABELS, uuid);
                    // need to add as hard reference to version history, so that it does not get fluhed.
                    state.addStateReference(vlns);
                    nodes.put(new NodeId(uuid), vlns);

                } else if (vi instanceof InternalVersion) {
                    InternalVersion v = (InternalVersion) vi;
                    state = new VersionNodeState(this, v, vi.getParent().getId());
                    state.setDefinitionId(NDEF_VERSION);
                    state.setPropertyValue(JCR_CREATED, InternalValue.create(v.getCreated()));
                    // todo: do not read frozen stuff from frozen node instance here, rather put to version
                    //state.setPropertyValue(JCR_FROZENUUID, InternalValue.create(v.getFrozenNode().getFrozenUUID()));
                    //state.setPropertyValue(JCR_FROZENPRIMARYTYPE, InternalValue.create(v.getFrozenNode().getFrozenPrimaryType()));
                    //state.setPropertyValues(JCR_FROZENMIXINTYPES, PropertyType.NAME, InternalValue.create(v.getFrozenNode().getFrozenMixinTypes()));
                    //state.setPropertyValues(JCR_VERSIONLABELS, PropertyType.STRING, InternalValue.create(v.getLabels()));
                    state.setPropertyValues(JCR_PREDECESSORS, PropertyType.REFERENCE, new InternalValue[0]);
                    state.setPropertyValues(JCR_SUCCESSORS, PropertyType.REFERENCE, new InternalValue[0]);

                } else if (vi instanceof InternalFrozenNode) {
                    InternalFrozenNode fn = (InternalFrozenNode) vi;
                    VirtualNodeState parent = getNodeState(new NodeId(fn.getParent().getId()));
                    boolean mimicFrozen = !(parent instanceof VersionNodeState);
                    state = createNodeState(parent,
                            JCR_FROZENNODE,
                            id.getUUID(),
                            mimicFrozen ? fn.getFrozenPrimaryType() :
                            NT_FROZENNODE);
                    mapFrozenNode(state, fn, mimicFrozen);

                } else if (vi instanceof InternalFrozenVersionHistory) {
                    InternalFrozenVersionHistory fn = (InternalFrozenVersionHistory) vi;
                    VirtualNodeState parent = getNodeState(new NodeId(fn.getParent().getId()));
                    state = createNodeState(parent,
                            fn.getName(),
                            id.getUUID(),
                            NT_VERSIONEDCHILD);
                    // IMO, this should be exposed aswell
                    // state.setPropertyValue(JCR_BASE_VERSION, InternalValue.create(UUID.fromString(fn.getBaseVersionId())));
                    state.setPropertyValue(JCR_CHILD, InternalValue.create(UUID.fromString(fn.getVersionHistoryId())));
                } else {
                    // not found, throw
                    throw new NoSuchItemStateException(id.toString());
                }
            } catch (RepositoryException e) {
                log.error("Unable to check for item:" + e.toString());
                throw new ItemStateException(e);
            }

            // add state to cache
            nodes.put(id, state);
            log.debug("item added to cache. size=" + nodes.size());
        }
        return state;
    }

    /**
     * @see VirtualItemStateProvider#hasPropertyState(PropertyId)
     */
    public boolean hasPropertyState(PropertyId id) {

        try {
            // get parent state
            NodeState parent = getNodeState(new NodeId(id.getParentUUID()));

            // handle some default prop states
            if (parent instanceof VirtualNodeState) {
                return ((VirtualNodeState) parent).hasPropertyEntry(id.getName());
            }
        } catch (ItemStateException e) {
            // ignore
        }
        return false;
    }

    /**
     * @see VirtualItemStateProvider#getPropertyState(PropertyId)
     */
    public VirtualPropertyState getPropertyState(PropertyId id)
            throws NoSuchItemStateException, ItemStateException {

        // get parent state
        NodeState parent = getNodeState(new NodeId(id.getParentUUID()));

        // handle some default prop states
        if (parent instanceof VirtualNodeState) {
            return ((VirtualNodeState) parent).getProperty(id.getName());
        }
        throw new NoSuchItemStateException(id.toString());
    }

    /**
     * @see VirtualItemStateProvider#createPropertyState(VirtualNodeState, QName, int, boolean)
     */
    public VirtualPropertyState createPropertyState(VirtualNodeState parent,
                                                    QName name, int type,
                                                    boolean multiValued)
            throws RepositoryException {
        PropDef def = getApplicablePropertyDef(parent, name, type, multiValued);
        VirtualPropertyState prop = new VirtualPropertyState(name, parent.getUUID());
        prop.setType(type);
        prop.setMultiValued(multiValued);
        prop.setDefinitionId(new PropDefId(def));
        return prop;
    }

    /**
     * @see VirtualItemStateProvider#createNodeState(VirtualNodeState, QName, String, QName)
     */
    public VirtualNodeState createNodeState(VirtualNodeState parent, QName name,
                                            String uuid, QName nodeTypeName)
            throws RepositoryException {

        NodeDefId def;
        try {
            def = new NodeDefId(getApplicableChildNodeDef(parent, name, nodeTypeName));
        } catch (RepositoryException re) {
            // hack, use nt:unstructured as parent
            NodeTypeRegistry ntReg = getNodeTypeRegistry();
            EffectiveNodeType ent = ntReg.getEffectiveNodeType(NT_UNSTRUCTURED);
            ChildNodeDef cnd = ent.getApplicableChildNodeDef(name, nodeTypeName);
            ntReg.getNodeDef(new NodeDefId(cnd));
            def = new NodeDefId(cnd);
        }

        // create a new node state
        VirtualNodeState state = null;
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();	// version 4 uuid
        }
        state = new VirtualNodeState(this, parent.getUUID(), uuid, nodeTypeName, new QName[0]);
        state.setDefinitionId(def);

        nodes.put(state.getId(), state);
        return state;
    }

    //-----------------------------------------------------< internal stuff >---

    /**
     * returns the node type manager
     *
     * @return
     */
    private NodeTypeRegistry getNodeTypeRegistry() {
        return ntReg;
    }

    /**
     * maps a frozen node
     *
     * @param state
     * @param node
     * @return
     * @throws RepositoryException
     */
    private VirtualNodeState mapFrozenNode(VirtualNodeState state,
                                           InternalFrozenNode node,
                                           boolean mimicFrozen)
            throws RepositoryException {

        if (mimicFrozen) {
            if (node.getFrozenUUID() != null) {
                state.setPropertyValue(JCR_UUID, InternalValue.create(node.getFrozenUUID()));
            }
            state.setPropertyValues(JCR_MIXINTYPES, PropertyType.NAME, InternalValue.create(node.getFrozenMixinTypes()));
        } else {
            state.setPropertyValue(JCR_UUID, InternalValue.create(node.getId()));
            if (node.getFrozenUUID() != null) {
                state.setPropertyValue(JCR_FROZENUUID, InternalValue.create(node.getFrozenUUID()));
            }
            state.setPropertyValue(JCR_FROZENPRIMARYTYPE, InternalValue.create(node.getFrozenPrimaryType()));
            state.setPropertyValues(JCR_FROZENMIXINTYPES, PropertyType.NAME, InternalValue.create(node.getFrozenMixinTypes()));
        }

        // map properties
        PropertyState[] props = node.getFrozenProperties();
        for (int i = 0; i < props.length; i++) {
            if (props[i].isMultiValued()) {
                state.setPropertyValues(props[i].getName(), props[i].getType(), props[i].getValues());
            } else {
                state.setPropertyValue(props[i].getName(), props[i].getValues()[0]);
            }
        }
        // map child nodes
        InternalFreeze[] nodes = node.getFrozenChildNodes();
        for (int i = 0; i < nodes.length; i++) {
            state.addChildNodeEntry(nodes[i].getName(), nodes[i].getId());
        }
        return state;
    }

    /**
     * retrieves the property definition for the given contraints
     *
     * @param propertyName
     * @param type
     * @param multiValued
     * @return
     * @throws RepositoryException
     */
    protected PropDef getApplicablePropertyDef(NodeState parent, QName propertyName,
                                               int type, boolean multiValued)
            throws RepositoryException {
        return getEffectiveNodeType(parent).getApplicablePropertyDef(propertyName, type, multiValued);
    }

    /**
     * Retrieves the node definition for the given contraints.
     *
     * @param nodeName
     * @param nodeTypeName
     * @return
     * @throws RepositoryException
     */
    protected ChildNodeDef getApplicableChildNodeDef(NodeState parent, QName nodeName, QName nodeTypeName)
            throws RepositoryException {
        return getEffectiveNodeType(parent).getApplicableChildNodeDef(nodeName, nodeTypeName);
    }

    /**
     * Returns the effective (i.e. merged and resolved) node type representation
     * of this node's primary and mixin node types.
     *
     * @return the effective node type
     * @throws RepositoryException
     */
    protected EffectiveNodeType getEffectiveNodeType(NodeState parent) throws RepositoryException {
        // build effective node type of mixins & primary type
        NodeTypeRegistry ntReg = getNodeTypeRegistry();
        // existing mixin's
        HashSet set = new HashSet(parent.getMixinTypeNames());
        // primary type
        set.add(parent.getNodeTypeName());
        try {
            return ntReg.getEffectiveNodeType((QName[]) set.toArray(new QName[set.size()]));
        } catch (NodeTypeConflictException ntce) {
            String msg = "internal error: failed to build effective node type for node " + parent.getUUID();
            throw new RepositoryException(msg, ntce);
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean setNodeReferences(NodeReferences refs) {
        try {
            InternalVersionItem vi = vMgr.getItem(refs.getUUID());
            if (vi != null) {
                vMgr.setItemReferences(vi, refs.getReferences());
                return true;
            }
        } catch (RepositoryException e) {
            // ignore
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public void stateCreated(ItemState created) {
    }

    /**
     * {@inheritDoc}
     */
    public void stateModified(ItemState modified) {
    }

    /**
     * {@inheritDoc}
     */
    public void stateDestroyed(ItemState destroyed) {
        destroyed.removeListener(this);
        if (destroyed.isNode() && ((NodeState) destroyed).getUUID().equals(rootNodeId)) {
            try {
                createRootNodeState();
            } catch (RepositoryException e) {
                // ignore
            }
        }
        nodes.remove(destroyed.getId());
    }

    /**
     * {@inheritDoc}
     */
    public void stateDiscarded(ItemState discarded) {
        discarded.removeListener(this);
        if (discarded.isNode() && ((NodeState) discarded).getUUID().equals(rootNodeId)) {
            try {
                createRootNodeState();
            } catch (RepositoryException e) {
                // ignore
            }
        }
        nodes.remove(discarded.getId());
    }
}
