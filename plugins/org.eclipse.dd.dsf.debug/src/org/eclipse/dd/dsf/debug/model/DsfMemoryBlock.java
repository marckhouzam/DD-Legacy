/*******************************************************************************
 * Copyright (c) 2007, 2008 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *     Ericsson Communication - upgrade IF to IMemoryBlockExtension
 *     Ericsson Communication - added support for 64 bit processors
 *     Ericsson Communication - added support for changed bytes
 *     Ericsson Communication - better management of exceptions
 *******************************************************************************/
package org.eclipse.dd.dsf.debug.model;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;

import org.eclipse.cdt.core.IAddress;
import org.eclipse.cdt.utils.Addr64;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.PlatformObject;
import org.eclipse.core.runtime.Status;
import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.concurrent.Query;
import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.debug.internal.DsfDebugPlugin;
import org.eclipse.dd.dsf.debug.internal.provisional.model.IMemoryBlockUpdatePolicyProvider;
import org.eclipse.dd.dsf.debug.service.IMemory;
import org.eclipse.dd.dsf.debug.service.IRunControl;
import org.eclipse.dd.dsf.debug.service.IMemory.IMemoryChangedEvent;
import org.eclipse.dd.dsf.debug.service.IMemory.IMemoryDMContext;
import org.eclipse.dd.dsf.debug.service.IRunControl.StateChangeReason;
import org.eclipse.dd.dsf.service.DsfServiceEventHandler;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IMemoryBlockExtension;
import org.eclipse.debug.core.model.IMemoryBlockRetrieval;
import org.eclipse.debug.core.model.MemoryByte;

/**
 * This class manages the memory block retrieved from the target as a result
 * of a getBytesFromAddress() call from the platform.
 * 
 * It performs its read/write functions using the MemoryService.
 */
public class DsfMemoryBlock extends PlatformObject implements IMemoryBlockExtension, IMemoryBlockUpdatePolicyProvider
{
	private final static String UPDATE_POLICY_AUTOMATIC = "Automatic"; //$NON-NLS-1$
	private final static String UPDATE_POLICY_MANUAL = "Manual"; //$NON-NLS-1$
	private final static String UPDATE_POLICY_BREAKPOINT = "On Breakpoint"; //$NON-NLS-1$
	
	private final IMemoryDMContext fContext;
    private final ILaunch fLaunch;
    private final IDebugTarget fDebugTarget;
    private final DsfMemoryBlockRetrieval fRetrieval;
    private final String fModelId;
    private final String fExpression;
    private final BigInteger fBaseAddress;

    private BigInteger fBlockAddress;
    private int fLength;
    private int fWordSize;
    private MemoryByte[] fBlock;
    
    private String fUpdatePolicy = UPDATE_POLICY_AUTOMATIC;
    
    private ArrayList<Object> fConnections = new ArrayList<Object>();

    @SuppressWarnings("unused")
	private boolean isEnabled;

    /**
     * Constructor.
     * 
     * @param retrieval    - the MemoryBlockRetrieval (session context)
     * @param modelId      - 
     * @param expression   - the displayed expression in the UI
     * @param address      - the actual memory block start address
     * @param word_size    - the number of bytes per address
     * @param length       - the requested block length (could be 0)
     */
    DsfMemoryBlock(DsfMemoryBlockRetrieval retrieval, IMemoryDMContext context, String modelId, String expression, BigInteger address, int word_size, long length) {
    	fLaunch      = retrieval.getLaunch();
    	fDebugTarget = retrieval.getDebugTarget();
        fRetrieval   = retrieval;
        fContext     = context;
        fModelId     = modelId;
        fExpression  = expression;
        fBaseAddress = address;

        // Current block information
        fBlockAddress = address;
        fWordSize     = word_size;
        fLength       = (int) length;
        fBlock        = null;

        try {
            fRetrieval.getExecutor().execute(new Runnable() {
                public void run() {
                    fRetrieval.getSession().addServiceEventListener(DsfMemoryBlock.this, null);
                }
            });
        } catch (RejectedExecutionException e) {
            // Session is shut down.
        }
    }

    // ////////////////////////////////////////////////////////////////////////
    // IAdaptable
    // ////////////////////////////////////////////////////////////////////////

    /* (non-Javadoc)
     * @see org.eclipse.core.runtime.PlatformObject#getAdapter(java.lang.Class)
     */
    @SuppressWarnings("unchecked")
	@Override
    public Object getAdapter(Class adapter) {
        if (adapter.isAssignableFrom(DsfMemoryBlockRetrieval.class)) {
            return fRetrieval;
        }
        return super.getAdapter(adapter);
    }

    // ////////////////////////////////////////////////////////////////////////
    // IDebugElement
    // ////////////////////////////////////////////////////////////////////////

    /* (non-Javadoc)
     * @see org.eclipse.debug.core.model.IDebugElement#getDebugTarget()
     */
    public IDebugTarget getDebugTarget() {
        return fDebugTarget;
    }

    /* (non-Javadoc)
     * @see org.eclipse.debug.core.model.IDebugElement#getModelIdentifier()
     */
    public String getModelIdentifier() {
        return fModelId;
    }
    
    /* (non-Javadoc)
     * @see org.eclipse.debug.core.model.IDebugElement#getLaunch()
     */
    public ILaunch getLaunch() {
        return fLaunch;
    }

    // ////////////////////////////////////////////////////////////////////////
    // IMemoryBock interface - obsoleted by IMemoryBlockExtension
    // ////////////////////////////////////////////////////////////////////////

    /* (non-Javadoc)
     * @see org.eclipse.debug.core.model.IMemoryBlock#getStartAddress()
     */
    public long getStartAddress() {
    	// Not implemented (obsolete)
    	return 0;
    }

    /* (non-Javadoc)
     * @see org.eclipse.debug.core.model.IMemoryBlock#getLength()
     */
    public long getLength() {
    	// Not implemented (obsolete)
        return 0;
    }

    /* (non-Javadoc)
     * @see org.eclipse.debug.core.model.IMemoryBlock#getBytes()
     */
    public byte[] getBytes() throws DebugException {
    	// Not implemented (obsolete)
    	return new byte[0];
    }

    /* (non-Javadoc)
     * @see org.eclipse.debug.core.model.IMemoryBlock#supportsValueModification()
     */
    public boolean supportsValueModification() {
    	return fRetrieval.supportsValueModification();
    }

    /* (non-Javadoc)
     * @see org.eclipse.debug.core.model.IMemoryBlock#setValue(long, byte[])
     */
    public void setValue(long offset, byte[] bytes) throws DebugException {
    	// Not implemented (obsolete)
    }

    // ////////////////////////////////////////////////////////////////////////
    // IMemoryBlockExtension interface
    // ////////////////////////////////////////////////////////////////////////

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.IMemoryBlockExtension#getExpression()
	 */
	public String getExpression() {
		return fExpression;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.IMemoryBlockExtension#getBigBaseAddress()
	 */
	public BigInteger getBigBaseAddress() throws DebugException {
        return fBaseAddress;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.IMemoryBlockExtension#getMemoryBlockStartAddress()
	 */
	public BigInteger getMemoryBlockStartAddress() throws DebugException {
		// Null indicates that memory can be retrieved at addresses lower than the block base address
		return null;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.IMemoryBlockExtension#getMemoryBlockEndAddress()
	 */
	public BigInteger getMemoryBlockEndAddress() throws DebugException {
		// Null indicates that memory can be retrieved at addresses higher the block base address
		return null;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.IMemoryBlockExtension#getBigLength()
	 */
	public BigInteger getBigLength() throws DebugException {
		// -1 indicates that memory block is unbounded
		return BigInteger.valueOf(-1);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.IMemoryBlockExtension#getAddressSize()
	 */
	public int getAddressSize() throws DebugException {
		return fRetrieval.getAddressSize();
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.IMemoryBlockExtension#supportBaseAddressModification()
	 */
	public boolean supportBaseAddressModification() throws DebugException {
		return fRetrieval.supportBaseAddressModification();
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.IMemoryBlockExtension#supportsChangeManagement()
	 */
	public boolean supportsChangeManagement() {
		return true;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.IMemoryBlockExtension#setBaseAddress(java.math.BigInteger)
	 */
	public void setBaseAddress(BigInteger address) throws DebugException {
		fBlockAddress = address;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.IMemoryBlockExtension#getBytesFromOffset(java.math.BigInteger, long)
	 */
	public MemoryByte[] getBytesFromOffset(BigInteger offset, long units) throws DebugException {
		return getBytesFromAddress(fBlockAddress.add(offset), units);
	}
	
	private boolean fUseCachedData = false;
	
	public void clearCache() {
		fUseCachedData = false;
	}
	
	@DsfServiceEventHandler 
    public void handleCacheSuspendEvent(IRunControl.ISuspendedDMEvent e) {
		if (e.getReason() == StateChangeReason.BREAKPOINT)
			fUseCachedData = false;
	}
	
	private boolean isUseCacheData()
	{
		if (fUpdatePolicy.equals(DsfMemoryBlock.UPDATE_POLICY_BREAKPOINT))
			return fUseCachedData;

		if (fUpdatePolicy.equals(DsfMemoryBlock.UPDATE_POLICY_MANUAL))
			return fUseCachedData;

		return false;
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.IMemoryBlockExtension#getBytesFromAddress(java.math.BigInteger, long)
	 */
	public MemoryByte[] getBytesFromAddress(BigInteger address, long units) throws DebugException {

		if (isUseCacheData() && fBlockAddress.compareTo(address) == 0 && units * getAddressableSize() <= fBlock.length)
			return fBlock;
		
		MemoryByte[] newBlock = fetchMemoryBlock(address, units);
		int newLength = (newBlock != null) ? newBlock.length : 0;

		// If the retrieved block overlaps with the cached block, flag the changed bytes
		// so they can be properly highlighted in the platform Memory view. 
		// Note: In the standard Memory view, the values are displayed in cells of 4 bytes
		// (on 4-bytes address boundaries) and all 4 bytes have to be flagged so the
		// cell is highlighted (and the delta sigh is shown).
		if (fBlock != null && newLength > 0) {
			switch (fBlockAddress.compareTo(address))	{
				// Cached block begins before the retrieved block location
				// If there is no overlap, there are no bytes to flag
				case -1:
				{
					// Determine the distance between the cached and the
					// requested block addresses 
					BigInteger bigDistance = address.subtract(fBlockAddress);

					// If the distance does not exceed the length of the cached block,
					// then there is some overlap between the blocks and we have to
					// mark the changed bytes (if applicable)
					if (bigDistance.compareTo(BigInteger.valueOf(fLength)) == -1) {
						// Here we can reasonably assume that java integers are OK
						int distance = bigDistance.intValue(); 
						int length = fLength - distance;

						// Work by cells of 4 bytes
						for (int i = 0; i < length; i += 4) {
							// Determine if any byte within the cell was modified
							boolean changed = false;
							for (int j = i; j < (i + 4) && j < length; j++) {
								newBlock[j].setFlags(fBlock[distance + j].getFlags());
								if (newBlock[j].getValue() != fBlock[distance + j].getValue())
									changed = true;
							}
							// If so, flag the whole cell as modified
							if (changed)
								for (int j = i; j < (i + 4) && j < length; j++) {
									newBlock[j].setHistoryKnown(true);
									newBlock[j].setChanged(true);
								}
						}
						
					}
					break;
				}

				// Cached block begins before the retrieved block location
				// If there is no overlap, there are no bytes to flag
				// (this block of code is symmetric with the previous one)
				case 0:
				case 1:
				{
					// Determine the distance between the cached and the
					// requested block addresses 
					BigInteger bigDistance = fBlockAddress.subtract(address);

					// If the distance does not exceed the length of the new block,
					// then there is some overlap between the blocks and we have to
					// mark the changed bytes (if applicable)
					if (bigDistance.compareTo(BigInteger.valueOf(newLength)) == -1) {
						// Here we can reasonably assume that java integers are OK
						int distance = bigDistance.intValue(); 
						int length = newLength - distance;
						
						// Work by cells of 4 bytes
						for (int i = 0; i < length; i += 4) {
							// Determine if any byte within the cell was modified
							boolean changed = false;
							for (int j = i; j < (i + 4) && j < length; j++) {
								newBlock[distance + j].setFlags(fBlock[j].getFlags());
								if (newBlock[distance + j].getValue() != fBlock[j].getValue())
									changed = true;
							}
							// If so, flag the whole cell as modified
							if (changed)
								for (int j = i; j < (i + 4) && j < length; j++) {
									newBlock[distance + j].setHistoryKnown(true);
									newBlock[distance + j].setChanged(true);
								}
						}
					}
					break;
				}

				default:
					break;
			}
		}

		// Update the internal state
		fBlock = newBlock;
		fBlockAddress = address;
		fLength = newLength;
		
		if (fUpdatePolicy.equals(DsfMemoryBlock.UPDATE_POLICY_BREAKPOINT))
			fUseCachedData = true;
		else if (fUpdatePolicy.equals(DsfMemoryBlock.UPDATE_POLICY_MANUAL))
			fUseCachedData = true;

		return fBlock;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.IMemoryBlockExtension#setValue(java.math.BigInteger, byte[])
	 */
	public void setValue(BigInteger offset, byte[] bytes) throws DebugException {
		writeMemoryBlock(offset.longValue(), bytes);
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.IMemoryBlockExtension#connect(java.lang.Object)
	 */
	public void connect(Object client) {
		if (!fConnections.contains(client))
			fConnections.add(client);
		if (fConnections.size() == 1)
			enable();
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.IMemoryBlockExtension#disconnect(java.lang.Object)
	 */
	public void disconnect(Object client) {
		if (fConnections.contains(client))
			fConnections.remove(client);
		if (fConnections.size() == 0)
			disable();
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.IMemoryBlockExtension#getConnections()
	 */
	public Object[] getConnections() {
		return fConnections.toArray();
	}

	private void enable() {
		isEnabled = true;
	}

	private void disable() {
		isEnabled = false;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.IMemoryBlockExtension#dispose()
	 */
	public void dispose() throws DebugException {
		try {
    		fRetrieval.getExecutor().execute(new Runnable() {
    		    public void run() {
    		        fRetrieval.getSession().removeServiceEventListener(DsfMemoryBlock.this);
    		    }
    		});
		} catch (RejectedExecutionException e) {
		    // Session is down.
		}
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.IMemoryBlockExtension#getMemoryBlockRetrieval()
	 */
	public IMemoryBlockRetrieval getMemoryBlockRetrieval() {
		return fRetrieval;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.model.IMemoryBlockExtension#getAddressableSize()
	 */
	public int getAddressableSize() throws DebugException {
		return fRetrieval.getAddressableSize();
	}

    ///////////////////////////////////////////////////////////////////////////
    // Helper functions
    ///////////////////////////////////////////////////////////////////////////

    /*
	 * The real thing. Since the original call is synchronous (from a platform
	 * Job), we use a Query that will patiently wait for the underlying
	 * asynchronous calls to complete before returning.
	 * 
	 * @param bigAddress 
	 * @param length 
	 * @return MemoryByte[] 
	 * @throws DebugException
	 */
    private MemoryByte[] fetchMemoryBlock(BigInteger bigAddress, final long length) throws DebugException {

    	// For the IAddress interface
    	final Addr64 address = new Addr64(bigAddress);
    	
        // Use a Query to synchronize the downstream calls  
        Query<MemoryByte[]> query = new Query<MemoryByte[]>() {
			@Override
			protected void execute(final DataRequestMonitor<MemoryByte[]> drm) {
			    IMemory memoryService = (IMemory) fRetrieval.getServiceTracker().getService();
			    if (memoryService != null) {
			        // Go for it
			        memoryService.getMemory( 
			            fContext, address, 0, fWordSize, (int) length,
			            new DataRequestMonitor<MemoryByte[]>(fRetrieval.getExecutor(), drm) {
			                @Override
			                protected void handleSuccess() {
			                    drm.setData(getData());
			                    drm.done();
			                }
			            });
			    } else {
			    	drm.done();
			    }
				
			}
        };
        fRetrieval.getExecutor().execute(query);

		try {
            return query.get();
        } catch (InterruptedException e) {
    		throw new DebugException(new Status(IStatus.ERROR,
    				DsfDebugPlugin.PLUGIN_ID, DebugException.INTERNAL_ERROR,
    				"Error reading memory block (InterruptedException)", e)); //$NON-NLS-1$
        } catch (ExecutionException e) {
    		throw new DebugException(new Status(IStatus.ERROR,
    				DsfDebugPlugin.PLUGIN_ID, DebugException.INTERNAL_ERROR,
    				"Error reading memory block (ExecutionException)", e)); //$NON-NLS-1$
        }
    }

	/* Writes an array of bytes to memory.
     * 
     * @param offset
     * @param bytes
     * @throws DebugException
     */
    private void writeMemoryBlock(final long offset, final byte[] bytes) throws DebugException {

    	// For the IAddress interface
    	final Addr64 address = new Addr64(fBaseAddress);

        // Use a Query to synchronize the downstream calls  
        Query<MemoryByte[]> query = new Query<MemoryByte[]>() {
			@Override
			protected void execute(final DataRequestMonitor<MemoryByte[]> drm) {
			    IMemory memoryService = (IMemory) fRetrieval.getServiceTracker().getService();
			    if (memoryService != null) {
			        // Go for it
	    	        memoryService.setMemory(
		    	  	      fContext, address, offset, fWordSize, bytes.length, bytes,
		    	  	      new RequestMonitor(fRetrieval.getExecutor(), drm));
			    } else {
			    	drm.done();
			    }
				
			}
        };
        fRetrieval.getExecutor().execute(query);

		try {
            query.get();
        } catch (InterruptedException e) {
    		throw new DebugException(new Status(IStatus.ERROR,
    				DsfDebugPlugin.PLUGIN_ID, DebugException.INTERNAL_ERROR,
    				"Error writing memory block (InterruptedException)", e)); //$NON-NLS-1$
        } catch (ExecutionException e) {
    		throw new DebugException(new Status(IStatus.ERROR,
    				DsfDebugPlugin.PLUGIN_ID, DebugException.INTERNAL_ERROR,
    				"Error writing memory block (ExecutionException)", e)); //$NON-NLS-1$
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Event Handlers
    ///////////////////////////////////////////////////////////////////////////

    @DsfServiceEventHandler 
    public void eventDispatched(IRunControl.ISuspendedDMEvent e) {

    	// Clear the "Changed" flags after each run/resume/step
		for (int i = 0; i < fLength; i++)
			fBlock[i].setChanged(false);
    	
    	// Generate the MemoryChangedEvents
        handleMemoryChange(BigInteger.ZERO);
    }
    
    @DsfServiceEventHandler
    public void eventDispatched(IMemoryChangedEvent e) {

        // Check if we are in the same address space 
        if (e.getDMContext().equals(fContext)) {
        	IAddress[] addresses = e.getAddresses();
        	for (int i = 0; i < addresses.length; i++)
        		handleMemoryChange(addresses[i].getValue());
        }
    }
    
    /**
	 * @param address
	 * @param length
	 */
	public void handleMemoryChange(BigInteger address) {
		
		// Check if the change affects this particular block (0 is universal)
		BigInteger fEndAddress = fBlockAddress.add(BigInteger.valueOf(fLength));
		if (address.equals(BigInteger.ZERO) ||
		   ((fBlockAddress.compareTo(address) != 1) && (fEndAddress.compareTo(address) == 1)))
		{
			// Notify the event listeners
			DebugEvent debugEvent = new DebugEvent(this, DebugEvent.CHANGE, DebugEvent.CONTENT);
			DebugPlugin.getDefault().fireDebugEventSet(new DebugEvent[] { debugEvent });
		}
	}

	public String[] getUpdatePolicies() {
		return new String[] {UPDATE_POLICY_AUTOMATIC, UPDATE_POLICY_MANUAL, UPDATE_POLICY_BREAKPOINT};
	}
    
    public String getUpdatePolicy()
    {
    	return fUpdatePolicy;
    }
    
    public void setUpdatePolicy(String policy)
    {
    	fUpdatePolicy = policy;
    }
    
    public String getUpdatePolicyDescription(String id) {
		return id;
	}
}
