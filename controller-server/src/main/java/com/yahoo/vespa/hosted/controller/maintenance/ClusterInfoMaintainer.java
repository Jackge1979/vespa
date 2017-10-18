// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.maintenance;

import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.Flavor;
import com.yahoo.config.provision.Zone;
import com.yahoo.vespa.curator.Lock;
import com.yahoo.vespa.hosted.controller.Application;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.api.identifiers.DeploymentId;
import com.yahoo.vespa.hosted.controller.api.integration.configserver.NodeList;
import com.yahoo.vespa.hosted.controller.application.ClusterInfo;
import com.yahoo.vespa.hosted.controller.application.Deployment;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Maintain info about hardware, hostnames and cluster specifications.
 * <p>
 * This is used to calculate cost metrics for the application api.
 *
 * @author smorgrav
 */
public class ClusterInfoMaintainer extends Maintainer {

    private static final Logger log = Logger.getLogger(ClusterInfoMaintainer.class.getName());
    
    private final Controller controller;

    ClusterInfoMaintainer(Controller controller, Duration duration, JobControl jobControl) {
        super(controller, duration, jobControl);
        this.controller = controller;
    }

    private static String clusterid(NodeList.Node node) {
        return node.membership.clusterId;
    }

    private Map<ClusterSpec.Id, ClusterInfo> getClusterInfo(NodeList nodes, Zone zone) {
        Map<ClusterSpec.Id, ClusterInfo> infoMap = new HashMap<>();

        // Group nodes by clusterid
        Map<String, List<NodeList.Node>> clusters = nodes.nodes.stream()
                .filter(node -> node.membership != null)
                .collect(Collectors.groupingBy(ClusterInfoMaintainer::clusterid));

        // For each cluster - get info
        for (String id : clusters.keySet()) {
            List<NodeList.Node> clusterNodes = clusters.get(id);

            // Assume they are all equal and use first node as a representative for the cluster
            NodeList.Node node = clusterNodes.get(0);

            // Extract flavor info
            double cpu = 0;
            double mem = 0;
            double disk = 0;
            if (zone.nodeFlavors().isPresent()) {
                Optional<Flavor> flavorOptional = zone.nodeFlavors().get().getFlavor(node.flavor);
                if ((flavorOptional.isPresent())) {
                    Flavor flavor = flavorOptional.get();
                    cpu = flavor.getMinCpuCores();
                    mem = flavor.getMinMainMemoryAvailableGb();
                    disk = flavor.getMinMainMemoryAvailableGb();
                }
            }

            // Add to map
            List<String> hostnames = clusterNodes.stream().map(node1 -> node1.hostname).collect(Collectors.toList());
            ClusterInfo inf = new ClusterInfo(node.flavor, node.cost, cpu, mem, disk,
                                              ClusterSpec.Type.from(node.membership.clusterType), hostnames);
            infoMap.put(new ClusterSpec.Id(id), inf);
        }

        return infoMap;
    }

    @Override
    protected void maintain() {
        for (Application application : controller().applications().asList()) {
            try (Lock lock = controller().applications().lock(application.id())) {
                for (Deployment deployment : application.deployments().values()) {
                    DeploymentId deploymentId = new DeploymentId(application.id(), deployment.zone());
                    try {
                        NodeList nodes = controller().applications().configserverClient().getNodeList(deploymentId);
                        Map<ClusterSpec.Id, ClusterInfo> clusterInfo = getClusterInfo(nodes, deployment.zone());
                        Application app = application.with(deployment.withClusterInfo(clusterInfo));
                        controller.applications().store(app, lock);
                    } 
                    catch (IOException | IllegalArgumentException e) {
                        log.log(Level.WARNING, "Failing getting cluster info of for " + deploymentId, e);
                    }
                }
            }
        }
    }

}