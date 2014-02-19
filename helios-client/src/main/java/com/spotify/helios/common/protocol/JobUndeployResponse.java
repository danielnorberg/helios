package com.spotify.helios.common.protocol;

import com.google.common.base.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.spotify.helios.common.descriptors.JobId;

public class JobUndeployResponse {

  public enum Status {
    OK,
    JOB_NOT_FOUND,
    HOST_NOT_FOUND,
    INVALID_ID
  }

  private final Status status;
  private final JobId job;
  private final String host;

  public JobUndeployResponse(@JsonProperty("status") Status status,
                             @JsonProperty("host") String host,
                             @JsonProperty("job") JobId job) {
    this.status = status;
    this.job = job;
    this.host = host;
  }

  public Status getStatus() {
    return status;
  }

  public JobId getJob() {
    return job;
  }

  public String getHost() {
    return host;
  }

  @Override
  public String toString() {
    return Objects.toStringHelper("JobDeployResponse")
        .add("status", status)
        .add("host", host)
        .add("job", job)
        .toString();
  }
}