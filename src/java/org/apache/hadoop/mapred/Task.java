/**
 * Copyright 2005 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.mapred;

import org.apache.commons.logging.*;

import org.apache.hadoop.io.*;
import org.apache.hadoop.conf.*;
import org.apache.hadoop.util.*;

import java.io.*;

/** Base class for tasks. */
abstract class Task implements Writable, Configurable {
  private static final Log LOG =
    LogFactory.getLog("org.apache.hadoop.mapred.TaskRunner");

  ////////////////////////////////////////////
  // Fields
  ////////////////////////////////////////////

  private String jobFile;                         // job configuration file
  private String taskId;                          // unique, includes job id
  private String jobId;                           // unique jobid
  private int partition;                          // id within job
  
  ////////////////////////////////////////////
  // Constructors
  ////////////////////////////////////////////

  public Task() {}

  public Task(String jobId, String jobFile, String taskId, int partition) {
    this.jobFile = jobFile;
    this.taskId = taskId;
    this.jobId = jobId;
    this.partition = partition;
  }

  ////////////////////////////////////////////
  // Accessors
  ////////////////////////////////////////////
  public void setJobFile(String jobFile) { this.jobFile = jobFile; }
  public String getJobFile() { return jobFile; }
  public String getTaskId() { return taskId; }
  
  /**
   * Get the job name for this task.
   * @return the job name
   */
  public String getJobId() {
    return jobId;
  }
  
  /**
   * Get the index of this task within the job.
   * @return the integer part of the task id
   */
  public int getPartition() {
    return partition;
  }

  ////////////////////////////////////////////
  // Writable methods
  ////////////////////////////////////////////

  public void write(DataOutput out) throws IOException {
    UTF8.writeString(out, jobFile);
    UTF8.writeString(out, taskId);
    UTF8.writeString(out, jobId);
    out.writeInt(partition);
  }
  public void readFields(DataInput in) throws IOException {
    jobFile = UTF8.readString(in);
    taskId = UTF8.readString(in);
    jobId = UTF8.readString(in);
    partition = in.readInt();
  }

  public String toString() { return taskId; }

  /**
   * Localize the given JobConf to be specific for this task.
   */
  public void localizeConfiguration(JobConf conf) {
    conf.set("mapred.task.id", taskId);
    conf.setBoolean("mapred.task.is.map",isMapTask());
    conf.setInt("mapred.task.partition", partition);
    conf.set("mapred.job.id", jobId);
  }
  
  /** Run this task as a part of the named job.  This method is executed in the
   * child process and is what invokes user-supplied map, reduce, etc. methods.
   * @param umbilical for progress reports
   */
  public abstract void run(JobConf job, TaskUmbilicalProtocol umbilical)
    throws IOException;


  /** Return an approprate thread runner for this task. */
  public abstract TaskRunner createRunner(TaskTracker tracker
                                          ) throws IOException;

  /** The number of milliseconds between progress reports. */
  public static final int PROGRESS_INTERVAL = 1000;

  private transient Progress taskProgress = new Progress();
  private transient long nextProgressTime =
    System.currentTimeMillis() + PROGRESS_INTERVAL;

  public abstract boolean isMapTask();

  public Progress getProgress() { return taskProgress; }

  public Reporter getReporter(final TaskUmbilicalProtocol umbilical,
                              final Progress progress) throws IOException {
    return new Reporter() {
        public void setStatus(String status) throws IOException {
          progress.setStatus(status);
          progress();
        }
        public void progress() throws IOException {
            reportProgress(umbilical);
        }
      };
  }

  public void reportProgress(TaskUmbilicalProtocol umbilical, float progress)
    throws IOException {
    taskProgress.set(progress);
    reportProgress(umbilical);
  }

  public void reportProgress(TaskUmbilicalProtocol umbilical) {
    long now = System.currentTimeMillis();
    if (now > nextProgressTime)  {
      synchronized (this) {
        nextProgressTime = now + PROGRESS_INTERVAL;
        float progress = taskProgress.get();
        String status = taskProgress.toString();
        try {
          umbilical.progress(getTaskId(), progress, status);
        } catch (IOException ie) {
          LOG.warn(StringUtils.stringifyException(ie));
        }
      }
    }
  }

  public void done(TaskUmbilicalProtocol umbilical)
    throws IOException {
    umbilical.progress(getTaskId(),               // send a final status report
                       taskProgress.get(), taskProgress.toString());
    umbilical.done(getTaskId());
  }
}
