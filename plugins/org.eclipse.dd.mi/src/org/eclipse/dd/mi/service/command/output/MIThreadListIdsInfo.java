/*******************************************************************************
 * Copyright (c) 2000, 2006 QNX Software Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     QNX Software Systems - Initial API and implementation
 *     Ericsson AB			- Modified for DSF Reference Implementation
 *******************************************************************************/
package org.eclipse.dd.mi.service.command.output;

/**
 * GDB/MI thread list parsing.
 */
public class MIThreadListIdsInfo extends MIInfo {

	int[] threadIds;
	private String[] strThreadIds;

	public MIThreadListIdsInfo(MIOutput out) {
		super(out);
	}

	public int[] getThreadIds() {
		if (threadIds == null) {
			// To make sure that the threads have be parsed
			String[] tIds = getStrThreadIds();
			
			threadIds = new int[tIds.length];
			for (int i=0; i<tIds.length; i++) {
				try {
					threadIds[i] = Integer.parseInt(tIds[i]);
				} catch (NumberFormatException e) {
					threadIds[i] = 0;
				}
			}
		}

		return threadIds;
	}

	/**
     * @since 1.1
     */
	public String[] getStrThreadIds() {
		if (strThreadIds == null) {
			parse();
		}
		return strThreadIds;
	}

	void parse() {
		if (isDone()) {
			MIOutput out = getMIOutput();
			MIResultRecord rr = out.getMIResultRecord();
			if (rr != null) {
				MIResult[] results =  rr.getMIResults();
				for (int i = 0; i < results.length; i++) {
					String var = results[i].getVariable();
					if (var.equals("thread-ids")) { //$NON-NLS-1$
						MIValue val = results[i].getMIValue();
						if (val instanceof MITuple) {
							parseThreadIds((MITuple)val);
						}
					}
				}
			}
		}
		if (strThreadIds == null) {
			strThreadIds = new String[0];
		}
	}

	void parseThreadIds(MITuple tuple) {
		MIResult[] results = tuple.getMIResults();
		strThreadIds = new String[results.length];
		for (int i = 0; i < results.length; i++) {
			String var = results[i].getVariable();
			if (var.equals("thread-id")) { //$NON-NLS-1$
				MIValue value = results[i].getMIValue();
				if (value instanceof MIConst) {
					strThreadIds[i] = ((MIConst)value).getCString().trim();
				}
			}
		}
	}
}
