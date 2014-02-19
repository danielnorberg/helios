/**
 * Copyright (C) 2013 Spotify AB
 */

package com.spotify.helios.agent;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.AbstractIdleService;

import com.spotify.helios.common.descriptors.Goal;
import com.spotify.helios.common.descriptors.Job;
import com.spotify.helios.common.descriptors.JobId;
import com.spotify.helios.common.descriptors.Task;
import com.spotify.helios.servicescommon.PersistentAtomicReference;
import com.spotify.helios.servicescommon.Reactor;
import com.spotify.helios.servicescommon.ReactorFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import static com.google.common.base.Predicates.in;
import static com.google.common.base.Predicates.not;
import static com.spotify.helios.common.descriptors.Goal.START;
import static com.spotify.helios.common.descriptors.Goal.UNDEPLOY;
import static com.spotify.helios.common.descriptors.TaskStatus.State.STOPPED;
import static com.spotify.helios.servicescommon.Reactor.Callback;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Runs jobs to implement the desired container deployment state.
 */
public class Agent extends AbstractIdleService {

  public static final Map<JobId, Execution> EMPTY_EXECUTIONS = Collections.emptyMap();

  private static final Logger log = LoggerFactory.getLogger(Agent.class);

  private static final long UPDATE_INTERVAL = SECONDS.toMillis(30);

  private static final Predicate<Execution> PORTS_NOT_ALLOCATED = new Predicate<Execution>() {
    @Override
    public boolean apply(final Execution execution) {
      return execution.getPorts() == null;
    }
  };

  private final AgentModel model;
  private final SupervisorFactory supervisorFactory;

  private final ModelListener modelListener = new ModelListener();
  private final Map<JobId, Supervisor> supervisors = Maps.newHashMap();

  private final Reactor reactor;

  private final PersistentAtomicReference<Map<JobId, Execution>> executions;
  private final PortAllocator portAllocator;

  /**
   * Create a new agent.
   *
   * @param model             The model.
   * @param supervisorFactory The factory to use for creating supervisors.
   * @param reactorFactory    The factory to use for creating reactors.
   * @param executions        A persistent map of executions.
   * @param portAllocator     Allocator for job ports.
   */
  public Agent(final AgentModel model, final SupervisorFactory supervisorFactory,
               final ReactorFactory reactorFactory,
               final PersistentAtomicReference<Map<JobId, Execution>> executions,
               final PortAllocator portAllocator) {
    this.model = model;
    this.supervisorFactory = supervisorFactory;
    this.executions = executions;
    this.portAllocator = portAllocator;
    this.reactor = reactorFactory.create("agent", new Update(), UPDATE_INTERVAL);
  }

  @Override
  protected void startUp() throws Exception {
    for (final Entry<JobId, Execution> entry : this.executions.get().entrySet()) {
      final JobId id = entry.getKey();
      final Execution execution = entry.getValue();
      final Job job = execution.getJob();
      if (execution.getPorts() != null) {
        createSupervisor(id, job, execution.getPorts());
      }
    }
    model.addListener(modelListener);
    reactor.startAsync().awaitRunning();
    reactor.update();
  }

  @Override
  protected void shutDown() throws Exception {
    reactor.stopAsync().awaitTerminated();
    for (final Map.Entry<JobId, Supervisor> entry : supervisors.entrySet()) {
      entry.getValue().close();
    }
  }

  /**
   * Create a job supervisor.
   *
   * @param jobId      The name of the job.
   * @param descriptor The job descriptor.
   */
  private Supervisor createSupervisor(final JobId jobId, final Job descriptor,
                                      final Map<String, Integer> portAllocation) {
    log.debug("creating job supervisor: name={}, descriptor={}", jobId, descriptor);
    final Supervisor supervisor = supervisorFactory.create(jobId, descriptor, portAllocation);
    supervisors.put(jobId, supervisor);
    return supervisor;
  }

  /**
   * Instruct supervisor to start or stop job depending on the goal.
   */
  private void delegate(final Supervisor supervisor, final Goal goal)
  throws InterruptedException {
    switch (goal) {
      case START:
        if (!supervisor.isStarting()) {
          supervisor.start();
        }
        break;
      case STOP:
      case UNDEPLOY:
        if (!supervisor.isStopping()) {
          supervisor.stop();
        }
        break;
    }
  }

  /**
   * Listens to desired state updates.
   */
  private class ModelListener implements AgentModel.Listener {

    @Override
    public void tasksChanged(final AgentModel model) {
      reactor.update();
    }
  }

  /**
   * Starts and stops supervisors to reflect the desired state. Called by the reactor.
   */
  private class Update implements Callback {

    @Override
    public void run() throws InterruptedException {

      // Note: when changing this code:
      // * Ensure that supervisors for the same container never run concurrently.
      // * A supervisor must not be released before its container is stopped.
      // * A new container must either reuse an existing supervisor or wait for the old supervisor
      //   to die before spawning a new one.
      // * Book-keeping a supervisor of one job should not block processing of other jobs

      final Map<JobId, Task> tasks = model.getTasks();

      log.debug("tasks: {}", tasks.keySet());
      log.debug("executions: {}", executions.get());
      log.debug("supervisors: {}", supervisors);

      // Create and update executions
      final Map<JobId, Execution> newExecutions = Maps.newHashMap(executions.get());
      for (Entry<JobId, Task> entry : tasks.entrySet()) {
        final JobId jobId = entry.getKey();
        final Task task = entry.getValue();
        final Execution existing = newExecutions.get(jobId);
        if (existing != null) {
          if (existing.getGoal() != task.getGoal()) {
            final Execution execution = existing.withGoal(task.getGoal());
            newExecutions.put(jobId, execution);
          }
        } else if (task.getGoal() != UNDEPLOY) {
          newExecutions.put(jobId, Execution.of(task.getJob()).withGoal(task.getGoal()));
        }
      }

      // Allocate ports
      final Map<JobId, Execution> pending = ImmutableMap.copyOf(
          Maps.filterValues(newExecutions, PORTS_NOT_ALLOCATED));
      if (!pending.isEmpty()) {
        final ImmutableSet.Builder<Integer> usedPorts = ImmutableSet.builder();
        final Map<JobId, Execution> allocated = Maps.filterKeys(newExecutions,
                                                                not(in(pending.keySet())));
        for (final Entry<JobId, Execution> entry : allocated.entrySet()) {
          usedPorts.addAll(entry.getValue().getPorts().values());
        }

        for (final Entry<JobId, Execution> entry : pending.entrySet()) {
          final JobId jobId = entry.getKey();
          final Execution execution = entry.getValue();
          final Job job = execution.getJob();
          final Map<String, Integer> ports = portAllocator.allocate(job.getPorts(),
                                                                    usedPorts.build());
          log.debug("Allocated ports for job {}: {}", jobId, ports);
          if (ports != null) {
            newExecutions.put(jobId, execution.withPorts(ports));
            usedPorts.addAll(ports.values());
          } else {
            log.warn("Unable to allocate ports for job: {}", job);
          }
        }
      }

      // Persist executions
      if (!newExecutions.equals(executions.get())) {
        executions.setUnchecked(ImmutableMap.copyOf(newExecutions));
      }

      // Remove stopped supervisors.
      for (final Entry<JobId, Supervisor> entry : ImmutableSet.copyOf(supervisors.entrySet())) {
        final JobId jobId = entry.getKey();
        final Supervisor supervisor = entry.getValue();
        if (supervisor.isDone() && supervisor.getStatus() == STOPPED) {
          log.debug("releasing stopped supervisor: {}", jobId);
          supervisors.remove(jobId);
          supervisor.close();
          reactor.update();
        }
      }

      // Create new supervisors
      for (final Entry<JobId, Execution> entry : executions.get().entrySet()) {
        final JobId jobId = entry.getKey();
        final Execution execution = entry.getValue();
        final Supervisor supervisor = supervisors.get(jobId);
        if (supervisor == null &&
            execution.getGoal() == START &&
            execution.getPorts() != null) {
          createSupervisor(jobId, execution.getJob(), execution.getPorts());
        }
      }

      // Update supervisor goals
      for (final Map.Entry<JobId, Supervisor> entry : supervisors.entrySet()) {
        final JobId jobId = entry.getKey();
        final Supervisor supervisor = entry.getValue();
        final Execution execution = executions.get().get(jobId);
        delegate(supervisor, execution.getGoal());
      }

      // Reap dead executions
      final Set<JobId> reapedExecutions = Sets.newHashSet();
      for (Entry<JobId, Execution> entry : executions.get().entrySet()) {
        final JobId jobId = entry.getKey();
        final Execution execution = entry.getValue();
        if (execution.getGoal() == UNDEPLOY) {
          final Supervisor supervisor = supervisors.get(jobId);
          if (supervisor == null) {
            reapedExecutions.add(jobId);
            log.debug("Removing tombstoned task: {}", jobId);
            model.removeUndeployTombstone(jobId);
            model.removeTaskStatus(jobId);
          }
        }
      }

      // Persist executions
      if (!reapedExecutions.isEmpty()) {
        final Map<JobId, Execution> survivors = Maps.filterKeys(executions.get(),
                                                                not(in(reapedExecutions)));
        executions.setUnchecked(ImmutableMap.copyOf(survivors));
      }
    }
  }
}