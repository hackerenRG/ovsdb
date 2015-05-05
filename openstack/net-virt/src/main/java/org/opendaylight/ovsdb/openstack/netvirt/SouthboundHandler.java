/*
 * Copyright (C) 2013 Red Hat, Inc.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.ovsdb.openstack.netvirt;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import org.opendaylight.neutron.spi.NeutronNetwork;
import org.opendaylight.ovsdb.lib.notation.Row;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.openstack.netvirt.api.*;
import org.opendaylight.ovsdb.openstack.netvirt.impl.MdsalUtils;
import org.opendaylight.ovsdb.openstack.netvirt.impl.NeutronL3Adapter;
import org.opendaylight.ovsdb.schema.openvswitch.Bridge;
import org.opendaylight.ovsdb.schema.openvswitch.Interface;
import org.opendaylight.ovsdb.schema.openvswitch.OpenVSwitch;
import org.opendaylight.ovsdb.schema.openvswitch.Port;
import org.opendaylight.ovsdb.southbound.SouthboundMapper;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.ovsdb.rev150105.OvsdbBridgeAugmentation;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.ovsdb.rev150105.OvsdbTerminationPointAugmentation;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.node.TerminationPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Madhu Venugopal
 * @author Brent Salisbury
 * @author Dave Tucker
 * @author Sam Hague (shague@redhat.com)
 */
public class SouthboundHandler extends AbstractHandler
        implements NodeCacheListener, OvsdbInventoryListener {
    static final Logger logger = LoggerFactory.getLogger(SouthboundHandler.class);

    // The implementation for each of these services is resolved by the OSGi Service Manager
    private volatile ConfigurationService configurationService;
    private volatile BridgeConfigurationManager bridgeConfigurationManager;
    private volatile TenantNetworkManager tenantNetworkManager;
    private volatile NetworkingProviderManager networkingProviderManager;
    private volatile OvsdbConfigurationService ovsdbConfigurationService;
    private volatile OvsdbConnectionService connectionService;
    private volatile OvsdbInventoryService mdsalConsumer; // TODO SB_MIGRATION
    private volatile NeutronL3Adapter neutronL3Adapter;

    void start() {
        //this.triggerUpdates(); // TODO SB_MIGRATION
    }

    private SouthboundEvent.Type ovsdbTypeToSouthboundEventType(OvsdbType ovsdbType) {
        SouthboundEvent.Type type = SouthboundEvent.Type.NODE;

        switch (ovsdbType) {
            case NODE:
                type = SouthboundEvent.Type.NODE;
                break;
            case BRIDGE:
                type = SouthboundEvent.Type.BRIDGE;
                break;
            case PORT:
                type = SouthboundEvent.Type.PORT;
                break;
            case CONTROLLER:
                type = SouthboundEvent.Type.CONTROLLER;
                break;
            case OPENVSWITCH:
                type = SouthboundEvent.Type.OPENVSWITCH;
                break;
            default:
                logger.warn("Invalid OvsdbType: {}", ovsdbType);
                break;
        }
        return type;
    }

    @Override
    public void ovsdbUpdate(Node node, OvsdbType ovsdbType, Action action) {
        logger.info("ovsdbUpdate: {} - {} - {}", node, ovsdbType, action);
        this.enqueueEvent(new SouthboundEvent(node, ovsdbTypeToSouthboundEventType(ovsdbType), action));
    }

    public void processOvsdbNodeUpdate(Node node, Action action) {
        if (action == Action.ADD) {
            logger.info("processOvsdbNodeUpdate {}", node);
            bridgeConfigurationManager.prepareNode(node);
        } else {
            logger.info("Not implemented yet: {}", action);
        }
    }

    private void processRowUpdate(Node node, String tableName, String uuid, Row row,
                                  Object context, Action action) {
        /* TODO SB_MIGRATION */
        if (action == Action.DELETE) {
            if (tableName.equalsIgnoreCase(ovsdbConfigurationService.getTableName(node, Interface.class))) {
                logger.debug("Processing update of {}. Deleted node: {}, uuid: {}, row: {}", tableName, node, uuid, row);
                Interface deletedIntf = ovsdbConfigurationService.getTypedRow(node, Interface.class, row);
                NeutronNetwork network = null;
                if (context == null) {
                    network = tenantNetworkManager.getTenantNetwork(deletedIntf);
                } else {
                    network = (NeutronNetwork)context;
                }
                List<String> phyIfName = bridgeConfigurationManager.getAllPhysicalInterfaceNames(node);
                logger.info("Delete interface " + deletedIntf.getName());

                if (deletedIntf.getTypeColumn().getData().equalsIgnoreCase(NetworkHandler.NETWORK_TYPE_VXLAN) ||
                    deletedIntf.getTypeColumn().getData().equalsIgnoreCase(NetworkHandler.NETWORK_TYPE_GRE) ||
                    phyIfName.contains(deletedIntf.getName())) {
                    /* delete tunnel interfaces or physical interfaces */
                    //this.handleInterfaceDelete(node, uuid, deletedIntf, false, null);
                } else if (network != null && !network.getRouterExternal()) {
                    logger.debug("Processing update of {}:{} node {} intf {} network {}",
                            tableName, action, node, uuid, network.getNetworkUUID());
                    try {
                        ConcurrentMap<String, Row> interfaces = this.ovsdbConfigurationService
                                .getRows(node, ovsdbConfigurationService.getTableName(node, Interface.class));
                        if (interfaces != null) {
                            boolean isLastInstanceOnNode = true;
                            for (String intfUUID : interfaces.keySet()) {
                                if (intfUUID.equals(uuid)) continue;
                                Interface intf = this.ovsdbConfigurationService.getTypedRow(node, Interface.class, interfaces.get(intfUUID));
                                NeutronNetwork neutronNetwork = tenantNetworkManager.getTenantNetwork(intf);
                                if (neutronNetwork != null && neutronNetwork.equals(network)) isLastInstanceOnNode = false;
                            }
                            //this.handleInterfaceDelete(node, uuid, deletedIntf, isLastInstanceOnNode, network);
                        }
                    } catch (Exception e) {
                        logger.error("Error fetching Interface Rows for node " + node, e);
                    }
                }
            }
        } else if (tableName.equalsIgnoreCase(ovsdbConfigurationService.getTableName(node, OpenVSwitch.class))) {
            logger.debug("Processing update of {}:{} node: {}, ovs uuid: {}, row: {}", tableName, action, node, uuid, row);
            try {
                ConcurrentMap<String, Row> interfaces = this.ovsdbConfigurationService
                        .getRows(node, ovsdbConfigurationService.getTableName(node, Interface.class));
                if (interfaces != null) {
                    for (String intfUUID : interfaces.keySet()) {
                        Interface intf = ovsdbConfigurationService.getTypedRow(node, Interface.class, interfaces.get(intfUUID));
                        //this.handleInterfaceUpdate(node, intfUUID, intf);
                    }
                }
            } catch (Exception e) {
                logger.error("Error fetching Interface Rows for node " + node, e);
            }
        }
    }

    private void handleInterfaceUpdate (Node node, OvsdbTerminationPointAugmentation tp) {
        logger.trace("handleInterfaceUpdate node: {}, tp: {}", node, tp);
        NeutronNetwork network = tenantNetworkManager.getTenantNetwork(tp);
        if (network != null && !network.getRouterExternal()) {
            logger.trace("handleInterfaceUpdate node: {}, tp: {}, network: {}", node, tp, network.getNetworkUUID());
            tenantNetworkManager.programInternalVlan(node, tp, network);
            neutronL3Adapter.handleInterfaceEvent(node, tp, network, Action.UPDATE);
            if (bridgeConfigurationManager.createLocalNetwork(node, network)) {
                networkingProviderManager.getProvider(node).handleInterfaceUpdate(network, node, tp);
            }
        } else {
            logger.debug("No tenant network found on node: {} for interface: {}", node, tp);
        }
    }

    private void handleInterfaceDelete (Node node, OvsdbTerminationPointAugmentation intf, boolean isLastInstanceOnNode,
                                        NeutronNetwork network) {
        logger.debug("handleInterfaceDelete: node: {}, isLastInstanceOnNode: {}, interface: {}",
                node, isLastInstanceOnNode, intf);

        neutronL3Adapter.handleInterfaceEvent(node, intf, network, Action.DELETE);
        List<String> phyIfName = bridgeConfigurationManager.getAllPhysicalInterfaceNames(node);
        if (isInterfaceOfInterest(intf, phyIfName)) {
            /* delete tunnel or physical interfaces */
            //networkingProviderManager.getProvider(node).handleInterfaceDelete(intf.getTypeColumn().getData(), null,
            //        node, intf, isLastInstanceOnNode);
        } else if (network != null) {
            if (!network.getProviderNetworkType().equalsIgnoreCase(NetworkHandler.NETWORK_TYPE_VLAN)) { /* vlan doesn't need a tunnel endpoint */
                if (configurationService.getTunnelEndPoint(node) == null) {
                    logger.error("Tunnel end-point configuration missing. Please configure it in OpenVSwitch Table");
                    return;
                }
            }
            if (isLastInstanceOnNode & networkingProviderManager.getProvider(node).hasPerTenantTunneling()) {
                tenantNetworkManager.reclaimInternalVlan(node, network);
            }
            networkingProviderManager.getProvider(node).handleInterfaceDelete(network.getProviderNetworkType(), network, node, intf, isLastInstanceOnNode);
        }
    }

    private String getPortIdForInterface (Node node, String uuid, Interface intf) {
        /* TODO SB_MIGRATION */
        try {
            Map<String, Row> ports = this.ovsdbConfigurationService.getRows(node, ovsdbConfigurationService.getTableName(node, Port.class));
            if (ports == null) return null;
            for (String portUUID : ports.keySet()) {
                Port port = ovsdbConfigurationService.getTypedRow(node, Port.class, ports.get(portUUID));
                Set<UUID> interfaceUUIDs = port.getInterfacesColumn().getData();
                logger.trace("Scanning Port {} to identify interface : {} ",port, uuid);
                for (UUID intfUUID : interfaceUUIDs) {
                    if (intfUUID.toString().equalsIgnoreCase(uuid)) {
                        logger.trace("Found Interface {} -> {}", uuid, portUUID);
                        return portUUID;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to get Port tag for for Intf " + intf, e);
        }
        return null;
    }

    private void triggerUpdates() {
        /* TODO SB_MIGRATION */
        List<Node> nodes = connectionService.getNodes();
        if (nodes == null) return;
        for (Node node : nodes) {
            try {
                List<String> tableNames = ovsdbConfigurationService.getTables(node);
                if (tableNames == null) continue;
                for (String tableName : tableNames) {
                    Map<String, Row> rows = ovsdbConfigurationService.getRows(node, tableName);
                    if (rows == null) continue;
                    for (String uuid : rows.keySet()) {
                        Row row = rows.get(uuid);
                        //this.rowAdded(node, tableName, uuid, row);
                    }
                }
            } catch (Exception e) {
                logger.error("Exception during OVSDB Southbound update trigger", e);
            }
        }
    }

    private void processInterfaceDelete(Node node, String portName, Object context, Action action) {
        OvsdbTerminationPointAugmentation ovsdbTerminationPointAugmentation =
                MdsalUtils.getTerminationPointAugmentation(node, portName);
        logger.debug("processInterfaceDelete {}: {}", node, portName);
        NeutronNetwork network = null;
        if (context == null) {
            network = tenantNetworkManager.getTenantNetwork(ovsdbTerminationPointAugmentation);
        } else {
            network = (NeutronNetwork)context;
        }
        List<String> phyIfName = bridgeConfigurationManager.getAllPhysicalInterfaceNames(node);
        if (isInterfaceOfInterest(ovsdbTerminationPointAugmentation, phyIfName)) {
            this.handleInterfaceDelete(node, ovsdbTerminationPointAugmentation, false, null);
        } else if (network != null && !network.getRouterExternal()) {
            logger.debug("Network {} : Delete interface {} attached to bridge {}", network.getNetworkUUID(),
                    ovsdbTerminationPointAugmentation.getInterfaceUuid(), node);
            try {
                OvsdbBridgeAugmentation ovsdbBridgeAugmentation = node.getAugmentation(OvsdbBridgeAugmentation.class);
                if (ovsdbBridgeAugmentation != null) {
                    List<TerminationPoint> terminationPoints = node.getTerminationPoint();
                    if(!terminationPoints.isEmpty()){
                        boolean isLastInstanceOnNode = true;
                        for(TerminationPoint terminationPoint : terminationPoints) {
                            OvsdbTerminationPointAugmentation tpAugmentation =
                                    terminationPoint.getAugmentation( OvsdbTerminationPointAugmentation.class);
                            if(tpAugmentation.getInterfaceUuid().equals(ovsdbTerminationPointAugmentation.getInterfaceUuid())) continue;
                            NeutronNetwork neutronNetwork = tenantNetworkManager.getTenantNetwork(tpAugmentation);
                            if (neutronNetwork != null && neutronNetwork.equals(network)) {
                                isLastInstanceOnNode = false;
                                break;
                            }
                        }
                        this.handleInterfaceDelete(node, ovsdbTerminationPointAugmentation, isLastInstanceOnNode, network);
                    }
                }
            } catch (Exception e) {
                logger.error("Error fetching Interface Rows for node " + node, e);
            }
        }
    }

    private void processInterfaceUpdate(Node node, OvsdbTerminationPointAugmentation terminationPoint,
                                        String portName, Object context, Action action) {
        if (action == Action.DELETE) {
            processInterfaceDelete(node, portName, context, action);
        } else {

        }
    }

    private boolean isInterfaceOfInterest(OvsdbTerminationPointAugmentation terminationPoint, List<String> phyIfName) {
        return (SouthboundMapper.createOvsdbInterfaceType(
                terminationPoint.getInterfaceType()).equals(NetworkHandler.NETWORK_TYPE_VXLAN)
                ||
                SouthboundMapper.createOvsdbInterfaceType(
                        terminationPoint.getInterfaceType()).equals(NetworkHandler.NETWORK_TYPE_GRE)
                ||
                phyIfName.contains(terminationPoint.getName()));
    }

    /**
     * Notification about an OpenFlow Node
     *
     * @param openFlowNode the {@link Node Node} of interest in the notification
     * @param action the {@link Action}
     * @see NodeCacheListener#notifyNode
     */
    @Override
    public void notifyNode (Node openFlowNode, Action action) {
        logger.info("notifyNode: Node {} update {}", openFlowNode, action);

        if (action.equals(Action.ADD)) {
            networkingProviderManager.getProvider(openFlowNode).initializeOFFlowRules(openFlowNode);
        }
    }

    /**
     * Process the event.
     *
     * @param abstractEvent the {@link org.opendaylight.ovsdb.openstack.netvirt.AbstractEvent} event to be handled.
     * @see EventDispatcher
     */
    @Override
    public void processEvent(AbstractEvent abstractEvent) {
        if (!(abstractEvent instanceof SouthboundEvent)) {
            logger.error("Unable to process abstract event " + abstractEvent);
            return;
        }
        SouthboundEvent ev = (SouthboundEvent) abstractEvent;
        logger.info("processEvent: {}", ev);
        switch (ev.getType()) {
            case NODE:
                processOvsdbNodeUpdate(ev.getNode(), ev.getAction());
                break;
            case BRIDGE:
                processBridgeUpdate(ev.getNode(), ev.getAction());
                break;

            case PORT:
                processPortUpdate(ev.getNode(), ev.getAction());
                break;

            case OPENVSWITCH:
                processOpenVSwitchUpdate(ev.getNode(), ev.getAction());
                break;

            case ROW:
                try {
                    processRowUpdate(ev.getNode(), ev.getTableName(), ev.getUuid(), ev.getRow(),
                            ev.getContext(), ev.getAction());
                } catch (Exception e) {
                    logger.error("Exception caught in ProcessRowUpdate for node " + ev.getNode(), e);
                }
                break;
            default:
                logger.warn("Unable to process type " + ev.getType() +
                        " action " + ev.getAction() + " for node " + ev.getNode());
                break;
        }
    }

    private void processPortUpdate(Node node, Action action) {
        switch (action) {
            case ADD:
            case UPDATE:
                processPortUpdate(node);
                break;
            case DELETE:
                processPortDelete(node);
                break;
        }
    }

    private void processPortDelete(Node node) {
    }

    private void processPortUpdate(Node node) {
        List<TerminationPoint> terminationPoints = MdsalUtils.getTerminationPoints(node);
        for (TerminationPoint terminationPoint : terminationPoints) {
            processPortUpdate(node, terminationPoint.getAugmentation(OvsdbTerminationPointAugmentation.class));
        }
    }

    private void processOpenVSwitchUpdate(Node node, Action action) {
        //do the work that rowUpdate(table=openvswith) would have done
    }

    private void processPortUpdate(Node node, OvsdbTerminationPointAugmentation tp) {
        logger.debug("processPortUpdate {} - {}", node, tp);
        NeutronNetwork network = tenantNetworkManager.getTenantNetwork(tp);
        if (network != null && !network.getRouterExternal()) {
            this.handleInterfaceUpdate(node, tp);
        }

    }

    private void processBridgeUpdate(Node node, Action action) {
        OvsdbBridgeAugmentation bridge = MdsalUtils.getBridge(node);
        switch (action) {
            case ADD:
            case UPDATE:
                processBridgeUpdate(node, bridge);
                break;
            case DELETE:
                processBridgeDelete(node, bridge);
                break;
        }
    }

    private void processBridgeDelete(Node node, OvsdbBridgeAugmentation bridge) {
        logger.debug("processBridgeUpdate {}, {}", node, bridge);
    }

    private void processBridgeUpdate(Node node, OvsdbBridgeAugmentation bridge) {
        logger.debug("processBridgeUpdate {}, {}", node, bridge);
    }


}
