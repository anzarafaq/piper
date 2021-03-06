package com.creactiviti.piper.core;

import java.util.Date;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.creactiviti.piper.core.context.Context;
import com.creactiviti.piper.core.context.ContextRepository;
import com.creactiviti.piper.core.context.MapContext;
import com.creactiviti.piper.core.event.EventPublisher;
import com.creactiviti.piper.core.event.Events;
import com.creactiviti.piper.core.event.PiperEvent;
import com.creactiviti.piper.core.job.Job;
import com.creactiviti.piper.core.job.JobRepository;
import com.creactiviti.piper.core.job.JobStatus;
import com.creactiviti.piper.core.job.SimpleJob;
import com.creactiviti.piper.core.pipeline.Pipeline;
import com.creactiviti.piper.core.pipeline.PipelineRepository;
import com.creactiviti.piper.core.task.SimpleTaskExecution;
import com.creactiviti.piper.core.task.SpelTaskEvaluator;
import com.creactiviti.piper.core.task.TaskEvaluator;
import com.creactiviti.piper.core.task.TaskExecution;
import com.creactiviti.piper.core.task.TaskExecutionRepository;
import com.creactiviti.piper.core.task.TaskStatus;


/**
 * 
 * @author Arik Cohen
 * @since Apr 24, 2017
 */
public class DefaultTaskCompletionHandler implements TaskCompletionHandler {

  private Logger log = LoggerFactory.getLogger(getClass());
  
  private JobRepository jobRepository;
  private PipelineRepository pipelineRepository;
  private TaskExecutionRepository jobTaskRepository;
  private ContextRepository contextRepository;
  private JobExecutor jobExecutor;
  private EventPublisher eventPublisher;
  private TaskEvaluator taskEvaluator = new SpelTaskEvaluator(); 
  
  @Override
  public void handle (TaskExecution aTask) {
    log.debug("Completing task {}", aTask.getId());
    Job job = jobRepository.findJobByTaskId (aTask.getId());
    if(job!=null) {
      SimpleTaskExecution task = SimpleTaskExecution.createForUpdate(aTask);
      task.setStatus(TaskStatus.COMPLETED);
      jobTaskRepository.merge(task);
      SimpleJob mjob = new SimpleJob (job);
      if(task.getOutput() != null && task.getName() != null) {
        Context context = contextRepository.peek(job.getId());
        MapContext newContext = new MapContext(context.asMap());
        newContext.put(task.getName(), task.getOutput());
        contextRepository.push(job.getId(), newContext);
      }
      if(hasMoreTasks(mjob)) {
        mjob.setCurrentTask(mjob.getCurrentTask()+1);
        jobRepository.merge(mjob);
        jobExecutor.execute(mjob);
      }
      else {
        complete(mjob);
      }
    }
    else {
      log.error("Unknown job: {}",aTask.getJobId());
    }
  }

  private boolean hasMoreTasks (Job aJob) {
    Pipeline pipeline = pipelineRepository.findOne(aJob.getPipelineId());
    return aJob.getCurrentTask()+1 < pipeline.getTasks().size();
  }

  private void complete (SimpleJob aJob) {
    Pipeline pipeline = pipelineRepository.findOne(aJob.getPipelineId());
    List<Accessor> outputs = pipeline.getOutputs();
    Context context = contextRepository.peek(aJob.getId());
    SimpleTaskExecution jobOutput = SimpleTaskExecution.create(); 
    for(Accessor output : outputs) {
      jobOutput.set(output.getRequiredString(DSL.NAME), output.getRequiredString(DSL.VALUE));
    }
    TaskExecution evaledjobOutput = taskEvaluator.evaluate(jobOutput, context);
    SimpleJob job = new SimpleJob((Job)aJob);
    job.setStatus(JobStatus.COMPLETED);
    job.setEndTime(new Date ());
    job.setCurrentTask(-1);
    job.setOutputs(evaledjobOutput);
    jobRepository.merge(job);
    eventPublisher.publishEvent(PiperEvent.of(Events.JOB_STATUS, "jobId", aJob.getId(), "status", job.getStatus()));
    log.debug("Job {} completed successfully",aJob.getId());
  }
  
  public void setJobRepository(JobRepository aJobRepository) {
    jobRepository = aJobRepository;
  }
  
  public void setPipelineRepository(PipelineRepository aPipelineRepository) {
    pipelineRepository = aPipelineRepository;
  }

  public void setJobTaskRepository(TaskExecutionRepository aJobTaskRepository) {
    jobTaskRepository = aJobTaskRepository;
  }
  
  public void setContextRepository(ContextRepository aContextRepository) {
    contextRepository = aContextRepository;
  }
  
  public void setJobExecutor(JobExecutor aJobExecutor) {
    jobExecutor = aJobExecutor;
  }
  
  public void setEventPublisher(EventPublisher aEventPublisher) {
    eventPublisher = aEventPublisher;
  }

  @Override
  public boolean canHandle(TaskExecution aJobTask) {
    return aJobTask.getParentId()==null;
  }

}
