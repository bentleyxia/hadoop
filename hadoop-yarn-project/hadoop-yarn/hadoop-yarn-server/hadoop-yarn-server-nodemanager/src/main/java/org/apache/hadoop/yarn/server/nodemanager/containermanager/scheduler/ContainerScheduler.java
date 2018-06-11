/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.nodemanager.containermanager.scheduler;

import com.google.common.annotations.VisibleForTesting;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.service.AbstractService;
import org.apache.hadoop.yarn.api.records.ContainerExitStatus;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ExecutionType;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceUtilization;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.event.AsyncDispatcher;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.server.api.records.ContainerQueuingLimit;
import org.apache.hadoop.yarn.server.api.records.OpportunisticContainersStatus;
import org.apache.hadoop.yarn.server.nodemanager.Context;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.container.Container;

import org.apache.hadoop.yarn.server.nodemanager.containermanager.container.ContainerImpl;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.linux.resources.ResourceHandlerChain;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.linux.resources.ResourceHandlerException;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.linux.resources.ResourceHandlerModule;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.monitor
    .ChangeMonitoringContainerResourceEvent;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.container.ContainerState;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.monitor.ContainersMonitor;


import org.apache.hadoop.yarn.server.nodemanager.metrics.NodeManagerMetrics;
import org.apache.hadoop.yarn.server.nodemanager.recovery.NMStateStoreService;
import org.apache.hadoop.yarn.server.nodemanager.recovery.NMStateStoreService
        .RecoveredContainerState;
import org.apache.hadoop.yarn.server.nodemanager.recovery.NMStateStoreService.RecoveredContainerStatus;
import org.apache.hadoop.yarn.util.resource.Resources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * The ContainerScheduler manages a collection of runnable containers. It
 * ensures that a container is launched only if all its launch criteria are
 * met. It also ensures that OPPORTUNISTIC containers are killed to make
 * room for GUARANTEED containers.
 */
public class ContainerScheduler extends AbstractService implements
    EventHandler<ContainerSchedulerEvent> {

  private static final Logger LOG =
      LoggerFactory.getLogger(ContainerScheduler.class);

  private final Context context;
  // Capacity of the queue for opportunistic Containers.
  private final int maxOppQueueLength;

  // Queue of Guaranteed Containers waiting for resources to run
  private final LinkedHashMap<ContainerId, Container>
      queuedGuaranteedContainers = new LinkedHashMap<>();
  // sum of the resources requested by guaranteed containers in queue
  private final Resource guaranteedResourcesDemanded =
      Resource.newInstance(0, 0);

  // Queue of Opportunistic Containers waiting for resources to run
  private final LinkedHashMap<ContainerId, Container>
      queuedOpportunisticContainers = new LinkedHashMap<>();

  // Used to keep track of containers that have been marked to be killed
  // or paused to make room for a guaranteed container.
  private final Map<ContainerId, Container> oppContainersToKill =
      new HashMap<>();
  // sum of the resources to be released by opportunistic containers that
  // have been marked to be killed or paused.
  private final Resource opportunisticResourcesToBeReleased =
      Resource.newInstance(0, 0);

  // Containers launched by the Scheduler will take a while to actually
  // move to the RUNNING state, but should still be fair game for killing
  // by the scheduler to make room for guaranteed containers. This holds
  // containers that are in RUNNING as well as those in SCHEDULED state that
  // have been marked to run, but not yet RUNNING.
  private final LinkedHashMap<ContainerId, Container> runningContainers =
      new LinkedHashMap<>();

  private final ContainerQueuingLimit queuingLimit =
      ContainerQueuingLimit.newInstance();

  private final OpportunisticContainersStatus opportunisticContainersStatus;

  // Resource Utilization Tracker that decides how utilization of the cluster
  // increases / decreases based on container start / finish
  private ResourceUtilizationTracker utilizationTracker;

  private final AsyncDispatcher dispatcher;
  private final NodeManagerMetrics metrics;

  private Boolean usePauseEventForPreemption = false;

  @VisibleForTesting
  ResourceHandlerChain resourceHandlerChain = null;

  /**
   * Instantiate a Container Scheduler.
   * @param context NodeManager Context.
   * @param dispatcher AsyncDispatcher.
   * @param metrics NodeManagerMetrics.
   */
  public ContainerScheduler(Context context, AsyncDispatcher dispatcher,
      NodeManagerMetrics metrics) {
    this(context, dispatcher, metrics, context.getConf().getInt(
        YarnConfiguration.NM_OPPORTUNISTIC_CONTAINERS_MAX_QUEUE_LENGTH,
        YarnConfiguration.
            DEFAULT_NM_OPPORTUNISTIC_CONTAINERS_MAX_QUEUE_LENGTH));
  }

  @VisibleForTesting
  public ContainerScheduler(Context context, AsyncDispatcher dispatcher,
      NodeManagerMetrics metrics, int qLength) {
    super(ContainerScheduler.class.getName());
    this.context = context;
    this.dispatcher = dispatcher;
    this.metrics = metrics;
    this.maxOppQueueLength = (qLength <= 0) ? 0 : qLength;
    this.opportunisticContainersStatus =
        OpportunisticContainersStatus.newInstance();
  }

  @Override
  public void serviceInit(Configuration conf) throws Exception {
    super.serviceInit(conf);
    try {
      if (resourceHandlerChain == null) {
        resourceHandlerChain = ResourceHandlerModule
            .getConfiguredResourceHandlerChain(conf, context);
      }
      if (LOG.isDebugEnabled()) {
        LOG.debug("Resource handler chain enabled = " + (resourceHandlerChain
            != null));
      }
      if (resourceHandlerChain != null) {
        LOG.debug("Bootstrapping resource handler chain");
        resourceHandlerChain.bootstrap(conf);
      }
    } catch (ResourceHandlerException e) {
      LOG.error("Failed to bootstrap configured resource subsystems! ", e);
      throw new IOException(
          "Failed to bootstrap configured resource subsystems!");
    }
    this.usePauseEventForPreemption =
        conf.getBoolean(
            YarnConfiguration.NM_CONTAINER_QUEUING_USE_PAUSE_FOR_PREEMPTION,
            YarnConfiguration.
                DEFAULT_NM_CONTAINER_QUEUING_USE_PAUSE_FOR_PREEMPTION);
    // We assume over allocation configurations have been initialized
    this.utilizationTracker = getResourceTracker();
  }

  private AllocationBasedResourceTracker getResourceTracker() {
    if (context.isOverAllocationEnabled()) {
      return new UtilizationBasedResourceTracker(this);
    } else {
      return new AllocationBasedResourceTracker(this);
    }
  }

  /**
   * Handle ContainerSchedulerEvents.
   * @param event ContainerSchedulerEvent.
   */
  @Override
  public void handle(ContainerSchedulerEvent event) {
    switch (event.getType()) {
    case SCHEDULE_CONTAINER:
      scheduleContainer(event.getContainer());
      break;
    // NOTE: Is sent only after container state has changed to PAUSED...
    case CONTAINER_PAUSED:
    // NOTE: Is sent only after container state has changed to DONE...
    case CONTAINER_COMPLETED:
      onResourcesReclaimed(event.getContainer());
      break;
    case UPDATE_CONTAINER:
      if (event instanceof UpdateContainerSchedulerEvent) {
        onUpdateContainer((UpdateContainerSchedulerEvent) event);
      } else {
        LOG.error("Unknown event type on UpdateContainer: " + event.getType());
      }
      break;
    case SHED_QUEUED_CONTAINERS:
      shedQueuedOpportunisticContainers();
      break;
    case RECOVERY_COMPLETED:
      startPendingContainers(false);
      break;
    case SCHEDULE_CONTAINERS:
      startPendingContainers(true);
      break;
    default:
      LOG.error("Unknown event arrived at ContainerScheduler: "
          + event.toString());
    }
  }

  /**
   * We assume that the ContainerManager has already figured out what kind
   * of update this is.
   */
  private void onUpdateContainer(UpdateContainerSchedulerEvent updateEvent) {
    ContainerId containerId = updateEvent.getContainer().getContainerId();
    if (updateEvent.isResourceChange()) {
      if (runningContainers.containsKey(containerId)) {
        this.utilizationTracker.containerReleased(
            new ContainerImpl(getConfig(), null, null, null, null,
                updateEvent.getOriginalToken(), context));
        this.utilizationTracker.containerLaunched(
            updateEvent.getContainer());
        getContainersMonitor().handle(
            new ChangeMonitoringContainerResourceEvent(containerId,
                updateEvent.getUpdatedToken().getResource()));
      }
    }

    if (updateEvent.isExecTypeUpdate()) {
      // Promotion or not (Increase signifies either a promotion
      // or container size increase)
      if (updateEvent.isIncrease()) {
        // Promotion of queued container..
        if (queuedOpportunisticContainers.remove(containerId) != null) {
          queuedGuaranteedContainers.put(containerId,
              updateEvent.getContainer());
          Resources.addTo(guaranteedResourcesDemanded,
              updateEvent.getContainer().getResource());
          startPendingContainers(true);
        }
      } else {
        // Demotion of queued container.. Should not happen too often
        // since you should not find too many queued guaranteed
        // containers
        if (queuedGuaranteedContainers.remove(containerId) != null) {
          Resources.subtractFrom(guaranteedResourcesDemanded,
              updateEvent.getContainer().getResource());
          queuedOpportunisticContainers.put(containerId,
              updateEvent.getContainer());
          startPendingContainers(false);
        }
      }
      try {
        resourceHandlerChain.updateContainer(updateEvent.getContainer());
      } catch (Exception ex) {
        LOG.warn(String.format("Could not update resources on " +
            "continer update of %s", containerId), ex);
      }
      startPendingContainers(maxOppQueueLength <= 0);
    }
  }

  /**
   * Populates auxiliary data structures used by the ContainerScheduler on
   * recovery.
   * @param container container recovered
   * @param rcs Recovered Container status
   */
  public void recoverActiveContainer(Container container,
      RecoveredContainerState rcs) {
    ExecutionType execType =
        container.getContainerTokenIdentifier().getExecutionType();
    if (rcs.getStatus() == RecoveredContainerStatus.QUEUED
        || rcs.getStatus() == RecoveredContainerStatus.PAUSED) {
      if (execType == ExecutionType.GUARANTEED) {
        queuedGuaranteedContainers.put(container.getContainerId(), container);
        Resources.addTo(guaranteedResourcesDemanded, container.getResource());
      } else if (execType == ExecutionType.OPPORTUNISTIC) {
        queuedOpportunisticContainers
            .put(container.getContainerId(), container);
      } else {
        LOG.error(
            "UnKnown execution type received " + container.getContainerId()
                + ", execType " + execType);
      }
    } else if (rcs.getStatus() == RecoveredContainerStatus.LAUNCHED) {
      runningContainers.put(container.getContainerId(), container);
      utilizationTracker.containerLaunched(container);
    }
    if (rcs.getStatus() != RecoveredContainerStatus.COMPLETED
            && rcs.getCapability() != null) {
      metrics.launchedContainer();
      metrics.allocateContainer(rcs.getCapability());
    }
  }

  /**
   * Return number of queued containers.
   * @return Number of queued containers.
   */
  public int getNumQueuedContainers() {
    return this.queuedGuaranteedContainers.size()
        + this.queuedOpportunisticContainers.size();
  }

  /**
   * Return the capacity of the queue for opportunistic containers
   * on this node.
   * @return queue capacity.
   */
  public int getOpportunisticQueueCapacity() {
    return this.maxOppQueueLength;
  }

  @VisibleForTesting
  public int getNumQueuedGuaranteedContainers() {
    return this.queuedGuaranteedContainers.size();
  }

  @VisibleForTesting
  public int getNumQueuedOpportunisticContainers() {
    return this.queuedOpportunisticContainers.size();
  }

  @VisibleForTesting
  public int getNumRunningContainers() {
    return this.runningContainers.size();
  }

  @VisibleForTesting
  public void setUsePauseEventForPreemption(
      boolean usePauseEventForPreemption) {
    this.usePauseEventForPreemption = usePauseEventForPreemption;
  }

  public OpportunisticContainersStatus getOpportunisticContainersStatus() {
    this.opportunisticContainersStatus.setQueuedOpportContainers(
        getNumQueuedOpportunisticContainers());
    this.opportunisticContainersStatus.setWaitQueueLength(
        getNumQueuedContainers());
    this.opportunisticContainersStatus.setOpportMemoryUsed(
        metrics.getAllocatedOpportunisticGB());
    this.opportunisticContainersStatus.setOpportCoresUsed(
        metrics.getAllocatedOpportunisticVCores());
    this.opportunisticContainersStatus.setRunningOpportContainers(
        metrics.getRunningOpportunisticContainers());
    this.opportunisticContainersStatus.setOpportQueueCapacity(
        getOpportunisticQueueCapacity());
    return this.opportunisticContainersStatus;
  }

  private void onResourcesReclaimed(Container container) {
    ContainerId containerId = container.getContainerId();

    // This could be killed externally for eg. by the ContainerManager,
    // in which case, the container might still be queued.
    if (queuedOpportunisticContainers.remove(containerId) != null) {
      return;
    }

    // This could be killed externally for eg. by the ContainerManager,
    // in which case, the container might still be queued.
    if (queuedGuaranteedContainers.remove(containerId) != null) {
      Resources.addTo(guaranteedResourcesDemanded, container.getResource());
      return;
    }

    if (oppContainersToKill.remove(containerId) != null) {
      Resources.subtractFrom(
          opportunisticResourcesToBeReleased, container.getResource());
    }

    // Requeue PAUSED containers
    if (container.getContainerState() == ContainerState.PAUSED) {
      if (container.getContainerTokenIdentifier().getExecutionType() ==
          ExecutionType.GUARANTEED) {
        queuedGuaranteedContainers.put(containerId, container);
        Resources.addTo(guaranteedResourcesDemanded, container.getResource());
      } else {
        queuedOpportunisticContainers.put(containerId, container);
      }
    }
    // decrement only if it was a running container
    Container completedContainer = runningContainers.remove(containerId);
    // only a running container releases resources upon completion
    boolean resourceReleased = completedContainer != null;
    if (resourceReleased) {
      this.utilizationTracker.containerReleased(container);
      if (container.getContainerTokenIdentifier().getExecutionType() ==
          ExecutionType.OPPORTUNISTIC) {
        this.metrics.completeOpportunisticContainer(container.getResource());
      }

      // In case of over-allocation being turned on, we may need to reclaim
      // more resources since the opportunistic containers that have been
      // killed or paused may have not released as much resource as we need.
      boolean reclaimOpportunisticResources = context.isOverAllocationEnabled();
      startPendingContainers(reclaimOpportunisticResources);
    }
  }

  /**
   * Start pending containers in the queue.
   * @param reclaimOpportunisticResources if set to true, resources allocated
   *                  to running OPPORTUNISTIC containers will be reclaimed in
   *                  cases where there are GUARANTEED containers being queued
   */
  private void startPendingContainers(boolean reclaimOpportunisticResources) {
    // When opportunistic container not allowed (which is determined by
    // max-queue length of pending opportunistic containers <= 0), start
    // guaranteed containers without looking at available resources and
    // skip scanning the queue of opportunistic containers
    if (maxOppQueueLength <= 0) {
      forcefullyStartGuaranteedContainers();
      return;
    }

    Resource available = utilizationTracker.getAvailableResources();

    // Start guaranteed containers that are queued, if resources available.
    boolean allGuaranteedContainersLaunched =
        startGuaranteedContainers(available);
    // Start opportunistic containers, if resources available, which is true
    // if all guaranteed containers in queue have been launched.
    if (allGuaranteedContainersLaunched) {
      startOpportunisticContainers(available);
    } else {
      // If not all guaranteed containers in queue are launched, we may need
      // to reclaim resources from opportunistic containers that are running.
      if (reclaimOpportunisticResources) {
        reclaimOpportunisticContainerResources();
      }
    }
  }

  /**
   * Try to launch as many GUARANTEED containers as possible.
   * @param available the amount of resources available to launch containers
   * @return true if all queued GUARANTEED containers are launched
   *              or there is no GUARANTEED containers to launch
   */
  private boolean startGuaranteedContainers(Resource available) {
    Iterator<Container> cIter =
        queuedGuaranteedContainers.values().iterator();
    boolean resourcesAvailable = true;
    while (cIter.hasNext() && resourcesAvailable) {
      Container container = cIter.next();
      if (isResourceAvailable(available, container)) {
        startContainer(container);
        Resources.subtractFrom(available, container.getResource());
        cIter.remove();
        Resources.subtractFrom(
            guaranteedResourcesDemanded, container.getResource());
      } else {
        resourcesAvailable = false;
      }
    }
    return resourcesAvailable;
  }

  /**
   * Launch all queued GUARANTEED containers without checking resource
   * availability. This is an optimization in cases where OPPORTUNISTIC
   * containers are not allowed on the node.
   */
  private void forcefullyStartGuaranteedContainers() {
    Iterator<Container> cIter =
        queuedGuaranteedContainers.values().iterator();
    while (cIter.hasNext()) {
      Container container = cIter.next();
      startContainer(container);
      cIter.remove();
      Resources.subtractFrom(
          guaranteedResourcesDemanded, container.getResource());
    }
  }
  /**
   * Try to launch as many OPPORTUNISTIC containers as possible.
   * @param available the amount of resources available to launch containers
   * @return true if all OPPORTUNISTIC containers are launched
   *              or there is no OPPORTUNISTIC containers to launch
   */
  private boolean startOpportunisticContainers(Resource available) {
    Iterator<Container> cIter =
        queuedOpportunisticContainers.values().iterator();
    boolean resourcesAvailable = true;
    while (cIter.hasNext() && resourcesAvailable) {
      Container container = cIter.next();
      if (isResourceAvailable(available, container)) {
        startContainer(container);
        Resources.subtractFrom(available, container.getResource());
        cIter.remove();
      } else {
        resourcesAvailable = false;
      }
    }
    return resourcesAvailable;
  }

  private static boolean isResourceAvailable(
      Resource resource, Container container) {
    Resource left = Resources.subtract(resource, container.getResource());
    return left.getMemorySize() >= 0 && left.getVirtualCores() >= 0;
  }

  private boolean enqueueContainer(Container container) {
    boolean isGuaranteedContainer = container.getContainerTokenIdentifier().
        getExecutionType() == ExecutionType.GUARANTEED;

    boolean isQueued;
    if (isGuaranteedContainer) {
      queuedGuaranteedContainers.put(container.getContainerId(), container);
      Resources.addTo(guaranteedResourcesDemanded, container.getResource());
      isQueued = true;
    } else {
      if (queuedOpportunisticContainers.size() < maxOppQueueLength) {
        LOG.info("Opportunistic container {} will be queued at the NM.",
            container.getContainerId());
        queuedOpportunisticContainers.put(
            container.getContainerId(), container);
        isQueued = true;
      } else {
        LOG.info("Opportunistic container [{}] will not be queued at the NM" +
                "since max queue length [{}] has been reached",
            container.getContainerId(), maxOppQueueLength);
        container.sendKillEvent(
            ContainerExitStatus.KILLED_BY_CONTAINER_SCHEDULER,
            "Opportunistic container queue is full.");
        isQueued = false;
      }
    }

    if (isQueued) {
      try {
        this.context.getNMStateStore().storeContainerQueued(
            container.getContainerId());
      } catch (IOException e) {
        LOG.warn("Could not store container [" + container.getContainerId()
            + "] state. The Container has been queued.", e);
      }
    }

    return isQueued;
  }

  @VisibleForTesting
  protected void scheduleContainer(Container container) {
    boolean isGuaranteedContainer = container.getContainerTokenIdentifier().
        getExecutionType() == ExecutionType.GUARANTEED;

    // Given a guaranteed container, we enqueue it first and then try to start
    // as many queuing guaranteed containers as possible followed by queuing
    // opportunistic containers based on remaining resources available. If the
    // container still stays in the queue afterwards, we need to preempt just
    // enough number of opportunistic containers.
    if (isGuaranteedContainer) {
      enqueueContainer(container);
      startPendingContainers(true);
    } else {
      // Given an opportunistic container, we first try to start as many queuing
      // guaranteed containers as possible followed by queuing opportunistic
      // containers based on remaining resource available, then enqueue the
      // opportunistic container. If the container is enqueued, we do another
      // pass to try to start the newly enqueued opportunistic container.
      startPendingContainers(false);
      boolean containerQueued = enqueueContainer(container);
      // container may not get queued because the max opportunistic container
      // queue length is reached. If so, there is no point doing another pass
      if (containerQueued) {
        startPendingContainers(false);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private void reclaimOpportunisticContainerResources() {
    List<Container> extraOppContainersToReclaim =
        pickOpportunisticContainersToReclaimResources();
    killOpportunisticContainers(extraOppContainersToReclaim);
  }

  private void killOpportunisticContainers(
      Collection<Container> containersToReclaim) {
    for (Container contToReclaim : containersToReclaim) {
      String preemptionAction = usePauseEventForPreemption == true ? "paused" :
          "preempted";
      LOG.info("Container {} will be {} to start the execution of guaranteed" +
              " containers.", contToReclaim.getContainerId(), preemptionAction);

      if (usePauseEventForPreemption) {
        contToReclaim.sendPauseEvent(
            "Container Paused to make room for Guaranteed Container");
      } else {
        contToReclaim.sendKillEvent(
            ContainerExitStatus.KILLED_BY_CONTAINER_SCHEDULER,
            "Container Killed to make room for Guaranteed Container.");
      }
      oppContainersToKill.put(contToReclaim.getContainerId(), contToReclaim);
      Resources.addTo(
          opportunisticResourcesToBeReleased, contToReclaim.getResource());
    }
  }

  private void startContainer(Container container) {
    LOG.info("Starting container [" + container.getContainerId()+ "]");
    // Skip to put into runningContainers and addUtilization when recover
    if (!runningContainers.containsKey(container.getContainerId())) {
      runningContainers.put(container.getContainerId(), container);
      this.utilizationTracker.containerLaunched(container);
    }
    if (container.getContainerTokenIdentifier().getExecutionType() ==
        ExecutionType.OPPORTUNISTIC) {
      this.metrics.startOpportunisticContainer(container.getResource());
    }
    container.sendLaunchEvent();
  }

  private List<Container> pickOpportunisticContainersToReclaimResources() {
    // The opportunistic containers that need to be killed for the
    // given container to start.
    List<Container> extraOpportContainersToKill = new ArrayList<>();
    // Track resources that need to be freed.
    ResourceUtilization resourcesToFreeUp = resourcesToFreeUp();

    // Go over the running opportunistic containers.
    // Use a descending iterator to kill more recently started containers.
    Iterator<Container> lifoIterator = new LinkedList<>(
        runningContainers.values()).descendingIterator();
    while(lifoIterator.hasNext() &&
        !hasSufficientResources(resourcesToFreeUp)) {
      Container runningCont = lifoIterator.next();
      if (runningCont.getContainerTokenIdentifier().getExecutionType() ==
          ExecutionType.OPPORTUNISTIC) {

        if (oppContainersToKill.containsKey(
            runningCont.getContainerId())) {
          // These containers have already been marked to be killed.
          // So exclude them..
          continue;
        }
        extraOpportContainersToKill.add(runningCont);
        // In the case of over-allocation, the running container may not
        // release as much resources as it has requested, but we'll check
        // again if more containers need to be killed/paused when this
        // container is released.
        ContainersMonitor.decreaseResourceUtilization(
            getContainersMonitor(), resourcesToFreeUp,
            runningCont.getResource());
      }
    }
    if (!hasSufficientResources(resourcesToFreeUp)) {
      LOG.warn("There are no sufficient resources to start guaranteed" +
          " containers at the moment. Opportunistic containers are in" +
          " the process of being killed to make room.");
    }
    return extraOpportContainersToKill;
  }

  private boolean hasSufficientResources(
      ResourceUtilization resourcesToFreeUp) {
    return resourcesToFreeUp.getPhysicalMemory() <= 0 &&
        resourcesToFreeUp.getVirtualMemory() <= 0 &&
        // Convert the number of cores to nearest integral number, due to
        // imprecision of direct float comparison.
        Math.round(resourcesToFreeUp.getCPU()
            * getContainersMonitor().getVCoresAllocatedForContainers()) <= 0;
  }

  /**
   * Determine how much resources are needed to be freed up to launch the given
   * GUARANTEED container. Used to determine how many running OPPORTUNISTIC
   * containers need to be killed/paused, assuming OPPORTUNISTIC containers to
   * be killed/paused will release the amount of resources they have requested.
   *
   * If the node is over-allocating itself, this may cause not enough
   * OPPORTUNISTIC containers being killed/paused in cases where the running
   * OPPORTUNISTIC containers are not consuming fully their resource requests.
   * We'd check again upon container completion events to see if more running
   * OPPORTUNISTIC containers need to be killed/paused.
   *
   * @return the amount of resource needed to be reclaimed for this container
   */
  private ResourceUtilization resourcesToFreeUp() {
    // Get allocation of currently allocated containers.
    ResourceUtilization resourceAllocationToFreeUp = ResourceUtilization
        .newInstance(0, 0, 0.0f);

    // Add to the allocation the allocation of pending guaranteed containers.
    ContainersMonitor.increaseResourceUtilization(getContainersMonitor(),
        resourceAllocationToFreeUp, guaranteedResourcesDemanded);

    // These resources are being freed, likely at the behest of another
    // guaranteed container..
    ContainersMonitor.decreaseResourceUtilization(getContainersMonitor(),
        resourceAllocationToFreeUp, opportunisticResourcesToBeReleased);

    // Deduct any remaining resources available
    Resource availableResources = utilizationTracker.getAvailableResources();
    if (availableResources.getVirtualCores() > 0 &&
        availableResources.getMemorySize() > 0) {
      ContainersMonitor.decreaseResourceUtilization(getContainersMonitor(),
          resourceAllocationToFreeUp, availableResources);
    }

    return resourceAllocationToFreeUp;
  }

  @SuppressWarnings("unchecked")
  public void updateQueuingLimit(ContainerQueuingLimit limit) {
    this.queuingLimit.setMaxQueueLength(limit.getMaxQueueLength());
    // YARN-2886 should add support for wait-times. Include wait time as
    // well once it is implemented
    if ((queuingLimit.getMaxQueueLength() > -1) &&
        (queuingLimit.getMaxQueueLength() <
            queuedOpportunisticContainers.size())) {
      dispatcher.getEventHandler().handle(
          new ContainerSchedulerEvent(null,
              ContainerSchedulerEventType.SHED_QUEUED_CONTAINERS));
    }
  }

  private void shedQueuedOpportunisticContainers() {
    int numAllowed = this.queuingLimit.getMaxQueueLength();
    Iterator<Container> containerIter =
        queuedOpportunisticContainers.values().iterator();
    while (containerIter.hasNext()) {
      Container container = containerIter.next();
      // Do not shed PAUSED containers
      if (container.getContainerState() != ContainerState.PAUSED) {
        if (numAllowed <= 0) {
          container.sendKillEvent(
              ContainerExitStatus.KILLED_BY_CONTAINER_SCHEDULER,
              "Container De-queued to meet NM queuing limits.");
          containerIter.remove();
          LOG.info(
              "Opportunistic container {} will be killed to meet NM queuing" +
                  " limits.", container.getContainerId());
        }
        numAllowed--;
      }
    }
  }

  public ContainersMonitor getContainersMonitor() {
    return this.context.getContainerManager().getContainersMonitor();
  }

  @VisibleForTesting
  public ResourceUtilization getCurrentUtilization() {
    return this.utilizationTracker.getCurrentUtilization();
  }
}
