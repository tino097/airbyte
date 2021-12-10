/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.temporal.scheduling;

import io.airbyte.workers.temporal.TemporalJobType;
import io.airbyte.workers.temporal.exception.NonRetryableException;
import io.airbyte.workers.temporal.scheduling.activities.ConfigFetchActivity;
import io.airbyte.workers.temporal.scheduling.activities.ConfigFetchActivity.ScheduleRetrieverInput;
import io.airbyte.workers.temporal.scheduling.activities.GenerateInputActivity;
import io.airbyte.workers.temporal.scheduling.activities.GenerateInputActivity.SyncInput;
import io.airbyte.workers.temporal.scheduling.activities.GenerateInputActivity.SyncOutput;
import io.airbyte.workers.temporal.scheduling.activities.JobCreationActivity;
import io.airbyte.workers.temporal.scheduling.activities.JobCreationActivity.AttemptCreationInput;
import io.airbyte.workers.temporal.scheduling.activities.JobCreationActivity.AttemptCreationOutput;
import io.airbyte.workers.temporal.scheduling.activities.JobCreationActivity.AttemptFailureInput;
import io.airbyte.workers.temporal.scheduling.activities.JobCreationActivity.JobCancelledInput;
import io.airbyte.workers.temporal.scheduling.activities.JobCreationActivity.JobCreationInput;
import io.airbyte.workers.temporal.scheduling.activities.JobCreationActivity.JobCreationOutput;
import io.airbyte.workers.temporal.scheduling.activities.JobCreationActivity.JobFailureInput;
import io.airbyte.workers.temporal.scheduling.activities.JobCreationActivity.JobSuccessInput;
import io.airbyte.workers.temporal.scheduling.shared.ActivityConfiguration;
import io.airbyte.workers.temporal.sync.SyncWorkflow;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ConnectionUpdaterWorkflowImpl implements ConnectionUpdaterWorkflow {

  public long NON_RUNNING_JOB_ID;
  public int NON_RUNNING_ATTEMPT_ID;

  private boolean isRunning = false;
  private boolean isDeleted = false;
  private boolean skipScheduling = false;
  private boolean isCancel = false;

  Optional<Long> maybeJobId = Optional.empty();
  Optional<Integer> maybeAttemptId = Optional.empty();

  private final GenerateInputActivity getSyncInputActivity = Workflow.newActivityStub(GenerateInputActivity.class, ActivityConfiguration.OPTIONS);
  private final JobCreationActivity jobCreationActivity = Workflow.newActivityStub(JobCreationActivity.class, ActivityConfiguration.OPTIONS);
  private final ConfigFetchActivity configFetchActivity = Workflow.newActivityStub(ConfigFetchActivity.class, ActivityConfiguration.OPTIONS);

  private CancellationScope syncWorkflowCancellationScope;

  public ConnectionUpdaterWorkflowImpl() {}

  @Override
  public SyncResult run(final ConnectionUpdaterInput connectionUpdaterInput) throws NonRetryableException {
    try {

      syncWorkflowCancellationScope = Workflow.newCancellationScope(() -> {
        // Scheduling
        final ConfigFetchActivity.ScheduleRetrieverInput scheduleRetrieverInput = new ScheduleRetrieverInput(
            connectionUpdaterInput.getConnectionId());
        final ConfigFetchActivity.ScheduleRetrieverOutput scheduleRetrieverOutput = configFetchActivity.getPeriodicity(scheduleRetrieverInput);
        Workflow.await(scheduleRetrieverOutput.getPeriodicity(), () -> skipScheduling() || connectionUpdaterInput.isFromFailure());

        // Job and attempt creation
        maybeJobId = Optional.ofNullable(connectionUpdaterInput.getJobId()).or(() -> {
          final JobCreationOutput jobCreationOutput = jobCreationActivity.createNewJob(new JobCreationInput(
              connectionUpdaterInput.getConnectionId()));
          return Optional.ofNullable(jobCreationOutput.getJobId());
        });

        final Optional<Integer> maybeAttemptId = Optional.ofNullable(connectionUpdaterInput.getAttemptId()).or(() -> maybeJobId.map(jobId -> {
          final AttemptCreationOutput attemptCreationOutput = jobCreationActivity.createNewAttempt(new AttemptCreationInput(
              jobId));
          return attemptCreationOutput.getAttemptId();
        }));

        // Sync workflow
        final SyncInput getSyncInputActivitySyncInput = new SyncInput(
            maybeAttemptId.get(),
            maybeJobId.get());

        final SyncOutput syncWorkflowInputs = getSyncInputActivity.getSyncWorkflowInput(getSyncInputActivitySyncInput);

        isRunning = true;

        final SyncWorkflow childSync = Workflow.newChildWorkflowStub(SyncWorkflow.class,
            ChildWorkflowOptions.newBuilder()
                .setWorkflowId("sync_" + maybeJobId.get())
                .setTaskQueue(TemporalJobType.SYNC.name())
                // This will cancel the child workflow when the parent is terminated
                .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_TERMINATE)
                .build());

        final UUID connectionId = connectionUpdaterInput.getConnectionId();

        log.error("Running for: " + connectionId);
        childSync.run(
            syncWorkflowInputs.getJobRunConfig(),
            syncWorkflowInputs.getSourceLauncherConfig(),
            syncWorkflowInputs.getDestinationLauncherConfig(),
            syncWorkflowInputs.getSyncInput(),
            connectionId);
      });

      try {
        syncWorkflowCancellationScope.run();
        syncWorkflowCancellationScope.wait();
      } catch (final CanceledFailure cf) {
        // When a scope is cancelled temporal will thow a CanceledFailure as you can see here:
        // https://github.com/temporalio/sdk-java/blob/master/temporal-sdk/src/main/java/io/temporal/workflow/CancellationScope.java#L72
        // The naming is very misleading, it is not a failure but the expected behavior...
      }
      log.error("Is canceled: " + isCancel);
      log.error("Is deleted: " + isDeleted);

      if (isDeleted) {
        // Stop the runs
        return new SyncResult(true);
      } else if (isCancel) {
        log.error("entering IsCancel");
        jobCreationActivity.jobCancelled(new JobCancelledInput(
            maybeJobId.get()));
      } else {
        // report success
        jobCreationActivity.jobSuccess(new JobSuccessInput(
            maybeJobId.get(),
            maybeAttemptId.get()));

        connectionUpdaterInput.setJobId(null);
        connectionUpdaterInput.setAttemptNumber(1);
        connectionUpdaterInput.setFromFailure(false);
      }
    } catch (final Exception e) {
      // TODO: Do we need to stop retrying at some points
      log.error("The connection update workflow has failed, will create a new attempt.", e);

      jobCreationActivity.attemptFailure(new AttemptFailureInput(
          connectionUpdaterInput.getJobId(),
          connectionUpdaterInput.getAttemptId()));

      final int maxAttempt = configFetchActivity.getMaxAttempt().getMaxAttempt();
      final int attemptNumber = connectionUpdaterInput.getAttemptNumber();

      if (maxAttempt > attemptNumber) {
        // restart from failure
        connectionUpdaterInput.setAttemptNumber(attemptNumber + 1);
        connectionUpdaterInput.setFromFailure(true);
      } else {
        jobCreationActivity.jobFailure(new JobFailureInput(
            connectionUpdaterInput.getJobId()));

        Workflow.await(Duration.ofMinutes(1), () -> skipScheduling());

        connectionUpdaterInput.setJobId(null);
        connectionUpdaterInput.setAttemptNumber(1);
        connectionUpdaterInput.setFromFailure(false);
      }
    } finally {
      // Continue the workflow as new
      connectionUpdaterInput.setAttemptId(null);
      resetState();
      Workflow.continueAsNew(connectionUpdaterInput);
    }
    // This should not be reachable as we always continue as new even if there is a failure
    return null;
  }

  @Override
  public void submitManualSync() {
    if (isRunning) {
      log.info("Can't schedule a manual workflow if a sync is running for this connection");
      return;
    }

    skipScheduling = true;
  }

  @Override
  public void cancelJob() {
    isCancel = true;
    syncWorkflowCancellationScope.cancel();
  }

  @Override
  public void deleteConnection() {
    isDeleted = true;
    cancelJob();
  }

  @Override
  public WorkflowState getState() {
    return new WorkflowState(
        isRunning,
        isDeleted,
        skipScheduling);
  }

  @Override
  public JobInformation getJobInformation() {
    return new JobInformation(
        maybeJobId.orElse(NON_RUNNING_JOB_ID),
        maybeAttemptId.orElse(NON_RUNNING_ATTEMPT_ID));
  }

  private Boolean skipScheduling() {
    return skipScheduling || isDeleted;
  }

  private void resetState() {
    isRunning = false;
    isDeleted = false;
    skipScheduling = false;
  }

}
