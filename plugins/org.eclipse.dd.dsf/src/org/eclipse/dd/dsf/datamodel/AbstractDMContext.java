/*******************************************************************************
 * Copyright (c) 2006 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.dsf.datamodel;

import org.eclipse.core.runtime.PlatformObject;
import org.eclipse.dd.dsf.concurrent.Immutable;
import org.eclipse.dd.dsf.service.DsfSession;

/**
 * Base implementation of the IDMContext interface.  Most functionality here 
 * is centered around comparing DMContexts, as this is a critical to make the 
 * views work correctly.
 * @param <V> Data Model data type that this context is for.
 */
@Immutable
abstract public class AbstractDMContext<V extends IDMData> extends PlatformObject 
    implements IDMContext<V>     
{
    private final String fSessionId;
    private final String fServiceFilter;
    private final IDMContext<?>[] fParents;

    /** 
     * Main constructor provides all data needed to implement the IModelContext
     * interface.
     */
    public AbstractDMContext(String sessionId, String filter, IDMContext<?>[] parents) {
        fSessionId = sessionId;
        fServiceFilter = filter;
        fParents = parents;
    }

    /** Convenience constructor */
    public AbstractDMContext(IDMService service, IDMContext<?>[] parents) {
        this(service.getSession().getId(), service.getServiceFilter(), parents);
    }

    /** 
     * Should be used by the deriving class to compare the basic context object
     * information.
     * @param other the other service to compare to
     * @return true if contexts are equal
     */
    protected boolean baseEquals(Object other) {
        if (other == null) return false;
        if ( !(other.getClass().equals(getClass()))) return false;
        IDMContext<?> otherCtx = (IDMContext<?>)other;
        return getSessionId().equals(otherCtx.getSessionId()) &&
               getServiceFilter().equals(otherCtx.getServiceFilter()) &&
               areParentsEqual(otherCtx.getParents());
    }

    private boolean areParentsEqual(IDMContext<?>[] otherParents) {
        if ( !(fParents.length == otherParents.length) ) return false;
        for (int i = 0; i < fParents.length; i++) {
            if (!fParents[i].equals(otherParents[i])) {
                return false;
            }
        }
        return true;
    }
    
    protected int baseHashCode() {
        int parentsHash = 0;
        for (Object parent : getParents()) {
            parentsHash += parent.hashCode();
        }
        return getSessionId().hashCode() + getServiceFilter().hashCode() + parentsHash;
    }
    
    protected String baseToString() {
        StringBuffer retVal = new StringBuffer(); 
        for (IDMContext<?> parent : fParents) {
            retVal.append(parent);
        }
        return retVal.toString(); 
    }
    
    public String getSessionId() { return fSessionId; }
    public String getServiceFilter() { return fServiceFilter; }
    public IDMContext<?>[] getParents() { return fParents; }
        
    /**
     * Overrides the standard platform getAdapter to provide session-specific 
     * adapters.
     * <p>
     * ModelContext is intended to be used in views, which call the 
     * contexts.getAdapter() method to retrieve model-specific content and 
     * label providers.  But since many different sessions could be active
     * at the same time, each requiring different content providers, the 
     * standard platform <code>IAdapterManager</code> is not sufficient in 
     * handling adapters for the model context object.  This is because 
     * <code>IAdapterManager</code> uses only the class of the adaptable to 
     * select the correct adapter factoru, while for model context, the 
     * session is equally important. 
     * @see org.eclipse.runtime.IAdapterManager 
     */
    @SuppressWarnings("unchecked")
    public Object getAdapter(Class adapterType) {
        Object retVal = null;
        DsfSession session = DsfSession.getSession(fSessionId);
        if (session != null) {
            retVal = session.getModelAdapter(adapterType);
        }
        if (retVal == null) {
            retVal = super.getAdapter(adapterType);
        }
        return retVal;
    }

}