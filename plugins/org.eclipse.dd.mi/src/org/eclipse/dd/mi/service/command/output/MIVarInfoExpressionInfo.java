/*******************************************************************************
 * Copyright (c) 2000, 2006 QNX Software Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     QNX Software Systems - Initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.mi.service.command.output;

/**
 * GDB/MI var-info-expression.
 */
public class MIVarInfoExpressionInfo extends MIInfo {

	String lang = ""; //$NON-NLS-1$
	String exp = ""; //$NON-NLS-1$

	public MIVarInfoExpressionInfo(MIOutput record) {
		super(record);
        if (isDone()) {
            MIOutput out = getMIOutput();
            MIResultRecord rr = out.getMIResultRecord();
            if (rr != null) {
                MIResult[] results =  rr.getMIResults();
                for (int i = 0; i < results.length; i++) {
                    String var = results[i].getVariable();
                    MIValue value = results[i].getMIValue();
                    String str = ""; //$NON-NLS-1$
                    if (value instanceof MIConst) {
                        str = ((MIConst)value).getString();
                    }

                    if (var.equals("lang")) { //$NON-NLS-1$
                        lang = str;
                    } else if (var.equals("exp")) { //$NON-NLS-1$
                        exp = str;
                    }
                }
            }
        }
	}

	public String getLanguage () {
		return lang;
	}

	public String getExpression() {
		return exp;
	}
}
