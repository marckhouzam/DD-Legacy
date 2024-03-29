/*******************************************************************************
 * Copyright (c) 2000, 2006 QNX Software Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     QNX Software Systems - Initial API and implementation
 *     Wind River Systems   - Modified for new DSF Reference Implementation
 *******************************************************************************/
package org.eclipse.dd.mi.service.command.output;

/**
 * GDB/MI var-evalute-expression
 */
public class MIVarEvaluateExpressionInfo extends MIInfo {

    String value = ""; //$NON-NLS-1$

    public MIVarEvaluateExpressionInfo(MIOutput record) {
    	super(record);
    	if (isDone()) {
    		MIOutput out = getMIOutput();
    		MIResultRecord rr = out.getMIResultRecord();
    		if (rr != null) {
    			MIResult[] results =  rr.getMIResults();
    			for (int i = 0; i < results.length; i++) {
    				String var = results[i].getVariable();
    				if (var.equals("value")) { //$NON-NLS-1$
    					MIValue val = results[i].getMIValue();
    					if (val instanceof MIConst) {
    						value = ((MIConst)val).getCString();
    					}
    				}
    			}
    		}
    	}
    }

    public String getValue () {
    	return value;
    }
}
