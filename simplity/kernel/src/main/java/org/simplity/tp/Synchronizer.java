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

package org.simplity.tp;

import org.simplity.kernel.Application;
import org.simplity.kernel.Tracer;
import org.simplity.kernel.db.DbDriver;
import org.simplity.kernel.value.Value;
import org.simplity.service.ServiceContext;

/**
 * spawns its actions into asynch threads and wait for all of them to complete
 * to proceed beyond this block. That is, this block action, as seen by its
 * parent, is synchronous, but it allows its child-actions to work in parallel
 *
 * @author simplity.org
 *
 */
public class Synchronizer extends Action {

	/**
	 * is there something to be done before spawning thread for child-actions?
	 */
	Action initialAction;

	/**
	 * is there something we want to do after all child-actions return?
	 */
	Action finalAction;

	/**
	 *
	 */
	Action[] actions;

	/*
	 * (non-Javadoc)
	 *
	 * @see
	 * org.simplity.tp.DbAction#doDbAct(org.simplity.service.ServiceContext,
	 * org.simplity.kernel.db.DbDriver)
	 */
	@Override
	protected Value delegate(ServiceContext ctx, DbDriver driver) {
		if (this.initialAction != null) {
			Value result = this.initialAction.act(ctx, driver);
			if (Value.intepretAsBoolean(result) == false) {
				Tracer.trace(
						"initialAction of Synchronizer has returned a value of "
								+ result
								+ " and hence the asynch actions are not executed");
				return Value.VALUE_TRUE;
			}
		}
		Tracer.trace("Going to create child-actions in prallel.");
		Thread[] threads = new Thread[this.actions.length];
		AsynchWorker[] workers = new AsynchWorker[this.actions.length];
		int i = 0;
		for (Action action : this.actions) {
			AsynchWorker worker = new AsynchWorker(ctx, action, driver);
			workers[i] = worker;
			Thread thread = Application.createThread(worker);
			threads[i] = thread;
			thread.start();
			i++;
		}
		Tracer.trace(
				"Parallel actions created. Waiting for all of them to finish their job.");
		for (Thread thread : threads) {
			try {
				if (thread.isAlive()){
					thread.join();
				}
			} catch (InterruptedException e) {
				Tracer.trace(
						"One of the threads got interrupted for Synchronizer "
								+ this.actionName);
			}
		}
		Tracer.trace("All child-actions returned");
		for (AsynchWorker worker : workers) {
			Tracer.trace(worker.getTrace());
		}
		if (this.finalAction != null) {
			return this.finalAction.act(ctx, driver);
		}
		return Value.VALUE_TRUE;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.simplity.tp.Action#getReady(int)
	 */
	@Override
	public void getReady(int idx, Service service) {
		super.getReady(idx, service);
		if (this.initialAction != null) {
			this.initialAction.getReady(0, service);
		}
		if (this.finalAction != null) {
			this.finalAction.getReady(0, service);
		}
		int i = 0;
		for (Action action : this.actions) {
			action.getReady(i++, service);
		}
	}

	class AsynchWorker implements Runnable {
		private final ServiceContext ctx;
		private final Action action;
		private final DbDriver driver;
		private String trace;

		AsynchWorker(ServiceContext ctx, Action action, DbDriver driver) {
			this.ctx = ctx;
			this.action = action;
			this.driver = driver;
		}

		String getTrace() {
			return this.trace;
		}

		/*
		 * (non-Javadoc)
		 *
		 * @see java.lang.Runnable#run()
		 */
		@Override
		public void run() {
			Tracer.startAccumulation();
			this.action.act(this.ctx, this.driver);
			this.trace = Tracer.stopAccumulation();
		}
	}
}

