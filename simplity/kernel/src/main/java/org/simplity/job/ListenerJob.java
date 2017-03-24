/*
 * Copyright (c) 2017 simplity.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.simplity.job;

import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import org.simplity.kernel.Tracer;
import org.simplity.kernel.value.Value;

/**
 * A job that is added to a scheduler. manages running jobs for the job that is scheduled
 * @author simplity.org
 *
 */
public class ListenerJob implements ScheduledJob{

	private final Job scheduledJob;
	private Value userId;
	private RunningJob[] runningJobs;
	private Object[] futures;
	private boolean isScheduled;

	ListenerJob(Job job, Value userId){
		this.scheduledJob = job;
		int nbr = job.nbrDedicatedThreads;
		this.runningJobs = new RunningJob[nbr];
		this.futures = new Object[nbr];
		if(this.userId == null){
			this.userId = userId;
		}
	}

	/* (non-Javadoc)
	 * @see org.simplity.job.ScheduledJob#schedule(java.util.concurrent.ScheduledThreadPoolExecutor)
	 */
	@Override
	public boolean schedule(ScheduledThreadPoolExecutor executor) {
		if(this.isScheduled){
			Tracer.trace(this.scheduledJob.name + " is already scheduled");
			return false;
		}
		for(int i = 0; i < this.runningJobs.length;i++){
			RunningJob rj = this.scheduledJob.createRunningJob(this.userId);
			this.runningJobs[i] = rj;
			this.futures[i] = executor.submit(rj);
		}
		this.isScheduled = true;
		return false;
	}

	/* (non-Javadoc)
	 * @see org.simplity.job.ScheduledJob#shutDownGracefully(java.util.concurrent.ScheduledThreadPoolExecutor)
	 */
	@Override
	public void cancel(ScheduledThreadPoolExecutor executor) {
		for(Object obj : this.futures){
			if(obj != null){
				((Future<?>)obj).cancel(true);
			}
		}
		this.isScheduled = false;
	}

	/* (non-Javadoc)
	 * @see org.simplity.job.ScheduledJob#incrmentThread(java.util.concurrent.ScheduledThreadPoolExecutor)
	 */
	@Override
	public void incrmentThread(ScheduledThreadPoolExecutor executor) {
		if(this.isScheduled == false){
			Tracer.trace(this.scheduledJob.name + " is not scheduled");
			return;
		}
		int nbr = this.runningJobs.length;
		RunningJob[] newJobs = new RunningJob[nbr + 1];
		this.copyJobs(this.runningJobs, newJobs, nbr);
		Object[] newFutures = new Object[nbr + 1];
		this.copyObjects(this.futures, newFutures, nbr);
		RunningJob rj  = this.scheduledJob.createRunningJob(this.userId);
		newJobs[nbr] = rj;
		newFutures[nbr] = executor.submit(rj);
		this.runningJobs = newJobs;
		this.futures = newFutures;

	}

	private void copyJobs(RunningJob[] fromJobs, RunningJob[] toJobs, int nbr){
		for(int i = 0; i < nbr; i++){
			toJobs[i] = fromJobs[i];
		}
	}

	private void copyObjects(Object[] fromObjs, Object[] toObjs, int nbr){
		for(int i = 0; i < nbr; i++){
			toObjs[i] = fromObjs[i];
		}
	}
	/* (non-Javadoc)
	 * @see org.simplity.job.ScheduledJob#decrmentThread(java.util.concurrent.ScheduledThreadPoolExecutor)
	 */
	@Override
	public void decrmentThread(ScheduledThreadPoolExecutor executor) {
		if(this.isScheduled == false){
			Tracer.trace(this.scheduledJob.name + " is not scheduled");
			return;
		}
		int nbr = this.runningJobs.length - 1;
		if(nbr == 0){
			Tracer.trace("Job " + this.scheduledJob.name + " has only one thread. Can not reduce it.");
			return;
		}
		RunningJob[] newJobs = new RunningJob[nbr];
		this.copyJobs(this.runningJobs, newJobs, nbr);
		Object[] newFutures = new Object[nbr];
		this.copyObjects(this.futures, newFutures, nbr);
		((Future<?>)this.futures[nbr]).cancel(true);

		this.futures = newFutures;
		this.runningJobs = newJobs;
	}

	/* (non-Javadoc)
	 * @see org.simplity.job.ScheduledJob#putStatus(java.util.List)
	 */
	@Override
	public void putStatus(List<RunningJobInfo> infoList) {
		int i = 0;
		String name = this.scheduledJob.name;
		String sname = this.scheduledJob.serviceName;

		for(RunningJob job : this.runningJobs){
			Future<?> f = (Future<?>)this.futures[i];
			JobStatus sts;
			if(f == null){
				sts = JobStatus.SCHEDULED;
			}else if(f.isCancelled()){
				sts = JobStatus.CANCELLED;
			}else{
				sts = job.jobStatus;
			}
			RunningJobInfo info = new RunningJobInfo(name, sname, sts, i++, "");
			infoList.add(info);
		}
	}
	/* (non-Javadoc)
	 * @see org.simplity.job.ScheduledJob#poll(int)
	 */
	@Override
	public int poll(int referenceMinutes) {
		return ScheduledJob.NEVER;
	}
}