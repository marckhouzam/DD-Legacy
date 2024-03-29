/*******************************************************************************
 * Copyright (c) 2000, 2007 QNX Software Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     QNX Software Systems - Initial API and implementation
 *     Wind River Systems   - Modified for new DSF Reference Implementation
 *******************************************************************************/

package org.eclipse.dd.mi.service.command.events;

import org.eclipse.dd.dsf.concurrent.Immutable;
import org.eclipse.dd.dsf.datamodel.AbstractDMEvent;
import org.eclipse.dd.dsf.datamodel.IDMContext;
import org.eclipse.dd.mi.service.command.output.MIResult;


/**
 */
@Immutable
public abstract class MIEvent<V extends IDMContext> extends AbstractDMEvent<V> {
    private final int fToken;
    private final MIResult[] fResults;

    public MIEvent(V dmc, int token, MIResult[] results) {
        super(dmc);
    	fToken = token;
    	fResults = results;
    }

    public int getToken() {
    	return fToken;
    }
    
    public MIResult[] getResults() {
        return fResults;
    }
    
    @Override
    public String toString() {
        if (fResults == null) {
            return super.toString();
        } else if (fResults.length == 1) {
            return fResults[0].toString();
        } else {
            StringBuilder builder = new StringBuilder();
            for (MIResult result : fResults) {
                builder.append(result);
                builder.append('\n');
            }
            return builder.toString();
        }
    }
}
