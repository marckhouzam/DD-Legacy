/*******************************************************************************
 * Copyright (c) 2006, 2008 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *     Ericsson 		  - Modified for handling of multiple execution contexts	
 *******************************************************************************/
package org.eclipse.dd.mi.service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

import org.eclipse.cdt.core.IAddress;
import org.eclipse.cdt.utils.Addr32;
import org.eclipse.cdt.utils.Addr64;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.datamodel.AbstractDMContext;
import org.eclipse.dd.dsf.datamodel.AbstractDMEvent;
import org.eclipse.dd.dsf.datamodel.DMContexts;
import org.eclipse.dd.dsf.datamodel.IDMContext;
import org.eclipse.dd.dsf.debug.service.ICachingService;
import org.eclipse.dd.dsf.debug.service.IExpressions;
import org.eclipse.dd.dsf.debug.service.IFormattedValues;
import org.eclipse.dd.dsf.debug.service.IRunControl;
import org.eclipse.dd.dsf.debug.service.IMemory.IMemoryChangedEvent;
import org.eclipse.dd.dsf.debug.service.IMemory.IMemoryDMContext;
import org.eclipse.dd.dsf.debug.service.IRegisters.IRegisterDMContext;
import org.eclipse.dd.dsf.debug.service.IRunControl.StateChangeReason;
import org.eclipse.dd.dsf.debug.service.IStack.IFrameDMContext;
import org.eclipse.dd.dsf.debug.service.command.CommandCache;
import org.eclipse.dd.dsf.debug.service.command.ICommandControlService;
import org.eclipse.dd.dsf.service.AbstractDsfService;
import org.eclipse.dd.dsf.service.DsfServiceEventHandler;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.dd.mi.internal.MIPlugin;
import org.eclipse.dd.mi.service.command.commands.ExprMetaGetAttributes;
import org.eclipse.dd.mi.service.command.commands.ExprMetaGetChildCount;
import org.eclipse.dd.mi.service.command.commands.ExprMetaGetChildren;
import org.eclipse.dd.mi.service.command.commands.ExprMetaGetValue;
import org.eclipse.dd.mi.service.command.commands.ExprMetaGetVar;
import org.eclipse.dd.mi.service.command.commands.MIDataEvaluateExpression;
import org.eclipse.dd.mi.service.command.output.ExprMetaGetAttributesInfo;
import org.eclipse.dd.mi.service.command.output.ExprMetaGetChildCountInfo;
import org.eclipse.dd.mi.service.command.output.ExprMetaGetChildrenInfo;
import org.eclipse.dd.mi.service.command.output.ExprMetaGetValueInfo;
import org.eclipse.dd.mi.service.command.output.ExprMetaGetVarInfo;
import org.eclipse.dd.mi.service.command.output.MIDataEvaluateExpressionInfo;
import org.osgi.framework.BundleContext;

/**
 * This class implements a debugger expression evaluator as a DSF service. The
 * primary interface that clients of this class should use is IExpressions.
 */
public class ExpressionService extends AbstractDsfService implements IExpressions, ICachingService {

	/**
	 * This class represents the two expressions that characterize an Expression Context.
	 */
	public static class ExpressionInfo {
        private final String fullExpression;
        private final String relativeExpression;
        
        public ExpressionInfo(String full, String relative) {
        	fullExpression = full;
        	relativeExpression = relative;
        }
        
        public String getFullExpr() { return fullExpression; }
        public String getRelExpr() { return relativeExpression; }
        
        @Override
        public boolean equals(Object other) {
        	if (other instanceof ExpressionInfo) {
                if (fullExpression == null ? ((ExpressionInfo) other).fullExpression == null : 
                	fullExpression.equals(((ExpressionInfo) other).fullExpression)) {
                	if (relativeExpression == null ? ((ExpressionInfo) other).relativeExpression == null : 
                		relativeExpression.equals(((ExpressionInfo) other).relativeExpression)) {
                		return true;
                	}
                }
        	}
        	return false;
        }

        @Override
        public int hashCode() {
        	return (fullExpression == null ? 0 : fullExpression.hashCode()) ^
        	       (relativeExpression == null ? 0 : relativeExpression.hashCode());
        }
        
        @Override
        public String toString() {
            return "[" + fullExpression +", " + relativeExpression + "]"; //$NON-NLS-1$//$NON-NLS-2$//$NON-NLS-3$
        }
	}
    /**
     * This class represents an expression.
     */
    protected static class MIExpressionDMC extends AbstractDMContext implements IExpressionDMContext {
        /**
         * This field holds an expression to be evaluated.
         */
    	private ExpressionInfo exprInfo;

        /**
         * ExpressionDMC Constructor for expression to be evaluated in context of 
         * a stack frame.
         * 
         * @param sessionId
         *            The session ID in which this context is created.
         * @param expression
         *            The expression to be described by this ExpressionDMC
         * @param relExpr
         *            The relative expression if this expression was created as a child
         * @param frameCtx
         *            The parent stack frame context for this ExpressionDMC. 
         */
        public MIExpressionDMC(String sessionId, String expression, String relExpr, IFrameDMContext frameCtx) {
            this(sessionId, expression, relExpr, (IDMContext)frameCtx);
        }

       /**
         * ExpressionDMC Constructor for expression to be evaluated in context of 
         * an thread.
         * 
         * @param sessionId
         *            The session ID in which this context is created.
         * @param expression
         *            The expression to be described by this ExpressionDMC
         * @param relExpr
         *            The relative expression if this expression was created as a child
         * @param execCtx
         *            The parent thread context for this ExpressionDMC. 
         */
        public MIExpressionDMC(String sessionId, String expression, String relExpr, IMIExecutionDMContext execCtx) {
            this(sessionId, expression, relExpr, (IDMContext)execCtx);
        }

        /**
         * ExpressionDMC Constructor for expression to be evaluated in context of 
         * a memory space.
         * 
         * @param sessionId
         *            The session ID in which this context is created.
         * @param expression
         *            The expression to be described by this ExpressionDMC
         * @param relExpr
         *            The relative expression if this expression was created as a child
         * @param memoryCtx
         *            The parent memory space context for this ExpressionDMC. 
         */
        public MIExpressionDMC(String sessionId, String expression, String relExpr, IMemoryDMContext memoryCtx) {
            this(sessionId, expression, relExpr, (IDMContext)memoryCtx);
        }

        private MIExpressionDMC(String sessionId, String expr, String relExpr, IDMContext parent) {
            super(sessionId, new IDMContext[] { parent });
            exprInfo = new ExpressionInfo(expr, relExpr);
        }

        /**
         * @return True if the two objects are equal, false otherwise.
         */
        @Override
        public boolean equals(Object other) {
            return super.baseEquals(other) && exprInfo.equals(((MIExpressionDMC)other).exprInfo);
        }

        /**
         * 
         * @return The hash code of this ExpressionDMC object.
         */
        @Override
        public int hashCode() {
            return super.baseHashCode() + exprInfo.hashCode();
        }

        /**
         * 
         * @return A string representation of this ExpressionDMC (including the
         *         expression to which it is bound).
         */
        @Override
        public String toString() {
            return baseToString() + ".expr" + exprInfo.toString(); //$NON-NLS-1$ 
        }

        /**
         * @return The full expression string represented by this ExpressionDMC
         */
        public String getExpression() {
            return exprInfo.getFullExpr();
        }
        
        /**
         * @return The relative expression string represented by this ExpressionDMC
         */
        public String getRelativeExpression() {
            return exprInfo.getRelExpr();
        }
    }
    
    protected static class InvalidContextExpressionDMC extends AbstractDMContext 
        implements IExpressionDMContext
    {
        private final String expression;

        public InvalidContextExpressionDMC(String sessionId, String expr, IDMContext parent) {
            super(sessionId, new IDMContext[] { parent });
            expression = expr;
        }

        @Override
        public boolean equals(Object other) {
            return super.baseEquals(other) && 
                expression == null ? ((InvalidContextExpressionDMC) other).getExpression() == null : expression.equals(((InvalidContextExpressionDMC) other).getExpression());
        }

        @Override
        public int hashCode() {
            return expression == null ? super.baseHashCode() : super.baseHashCode() ^ expression.hashCode();
        }

        @Override
        public String toString() {
            return baseToString() + ".invalid_expr[" + expression + "]"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        public String getExpression() {
            return expression;
        }
    }
    
    
	/**
	 * Contains the address of an expression as well as the size of its type.
	 */
    protected static class ExpressionDMAddress implements IExpressionDMAddress {
    	IAddress fAddr;
    	int fSize;
    	
    	public ExpressionDMAddress(IAddress addr, int size) {
    		fAddr = addr;
    		fSize = size;
    	}

    	public ExpressionDMAddress(String addrStr, int size) {
    		fSize = size;
    		// We must count the "0x" and that
    		// is why we compare with 10 characters
    		// instead of 8
    		if (addrStr.length() <= 10) {
    			fAddr = new Addr32(addrStr);
    		} else {
    			fAddr = new Addr64(addrStr);
    		}
    	}

    	public IAddress getAddress() { return fAddr; }
    	public int getSize() { return fSize; }
		
		@Override
		public boolean equals(Object other) {
			if (other instanceof ExpressionDMAddress) {
				ExpressionDMAddress otherAddr = (ExpressionDMAddress) other;
				return (fSize == otherAddr.getSize()) && 
    				(fAddr == null ? otherAddr.getAddress() == null : fAddr.equals(otherAddr.getAddress()));
			}
			return false;
		}

		@Override
		public int hashCode() {
			return (fAddr == null ? 0 :fAddr.hashCode()) + fSize;
		}

		@Override
		public String toString() {
			return (fAddr == null ? "null" : "(0x" + fAddr.toString()) + ", " + fSize + ")"; //$NON-NLS-1$ //$NON-NLS-2$//$NON-NLS-3$//$NON-NLS-4$
		}
    }

	/**
	 * This class represents the static data referenced by an instance of ExpressionDMC,
	 * such as its type and number of children; it does not contain the value or format
	 * of the expression.
	 */
	protected static class ExpressionDMData implements IExpressionDMData {
		// This is the relative expression, such as the name of a field within a structure,
		// in contrast to the fully-qualified expression contained in the ExpressionDMC,
		// which refers to the full name, including parent structure.
		private final String relativeExpression;
		private final String exprType;
		private final int numChildren;
		private final boolean editable;

		/**
		 * ExpressionDMData constructor.
		 */
		public ExpressionDMData(String expr, String type, int num, boolean edit) {
			relativeExpression = expr;
			exprType = type;
			numChildren = num;
			editable = edit;
		}

		public BasicType getBasicType() {
			return null;
		}
		
		public String getEncoding() {
			return null;
		}

		public Map<String, Integer> getEnumerations() {
			return new HashMap<String, Integer>();
		}

		public String getName() {
			return relativeExpression;
		}

		public IRegisterDMContext getRegister() {
			return null;
		}

		// See class VariableVMNode for an example of usage of this method
		public String getStringValue() {
			return null;
		}

		public String getTypeId() {
			return null;
		}

		public String getTypeName() {
			return exprType;
		}

		public int getNumChildren() {
			return numChildren;	
		}
		
		public boolean isEditable() {
			return editable;
		}
		
		@Override
		public boolean equals(Object other) {
			if (other instanceof ExpressionDMData) {
				ExpressionDMData otherData = (ExpressionDMData) other;
				return (getNumChildren() == otherData.getNumChildren()) && 
    				(getTypeName() == null ? otherData.getTypeName() == null : getTypeName().equals(otherData.getTypeName())) &&
    				(getName() == null ? otherData.getName() == null : getName().equals(otherData.getName()));
			}
			return false;
		}

		@Override
		public int hashCode() {
			return relativeExpression == null ? 0 : relativeExpression.hashCode() + 
					exprType == null ? 0 : exprType.hashCode() + numChildren;
		}

		@Override
		public String toString() {
			return "relExpr=" + relativeExpression + ", type=" + exprType + ", numchildren=" + numChildren; //$NON-NLS-1$ //$NON-NLS-2$//$NON-NLS-3$
		}
	}

	/**
	 * Event generated every time an expression is changed by the ExpressionService.
	 * 
	 * A client wishing to receive such events has to register as a service
	 * event listener and implement the corresponding eventDispatched method.
	 * 
	 * E.g.:
	 *
	 *    getSession().addServiceEventListener(listenerObject, null);
	 *     
	 *    @DsfServiceEventHandler
	 *    public void eventDispatched(ExpressionChangedEvent e) {
	 *       IExpressionDMContext context = e.getDMContext();
	 *       // do something...
	 *    }
	 */
    protected static class ExpressionChangedEvent extends AbstractDMEvent<IExpressionDMContext>
                        implements IExpressionChangedDMEvent {
    	
        public ExpressionChangedEvent(IExpressionDMContext context) {
        	super(context);
        }
    }

	private CommandCache fExpressionCache;
	private MIVariableManager varManager;

	
	public ExpressionService(DsfSession session) {
		super(session);
	}

	/**
	 * This method initializes this service.
	 * 
	 * @param requestMonitor
	 *            The request monitor indicating the operation is finished
	 */
	@Override
	public void initialize(final RequestMonitor requestMonitor) {
		super.initialize(new RequestMonitor(getExecutor(), requestMonitor) {
			@Override
			protected void handleSuccess() {
				doInitialize(requestMonitor);
			}
		});
	}
	
	/**
	 * This method initializes this service after our superclass's initialize()
	 * method succeeds.
	 * 
	 * @param requestMonitor
	 *            The call-back object to notify when this service's
	 *            initialization is done.
	 */
	private void doInitialize(RequestMonitor requestMonitor) {

		// Register to receive service events for this session.
        getSession().addServiceEventListener(this, null);
        
		// Register this service.
		register(new String[] { IExpressions.class.getName(),
				ExpressionService.class.getName() },
				new Hashtable<String, String>());
		
		// Create the expressionService-specific CommandControl which is our
        // variable object manager.
        // It will deal with the meta-commands, before sending real MI commands
        // to the back-end, through the MICommandControl service
		// It must be created after the ExpressionService is registered
		// since it will need to find it.
        varManager = new MIVariableManager(getSession(), getServicesTracker());

        // Create the meta command cache which will use the variable manager
        // to actually send MI commands to the back-end
        fExpressionCache = new CommandCache(getSession(), varManager);
        ICommandControlService commandControl = getServicesTracker().getService(ICommandControlService.class);
        fExpressionCache.setContextAvailable(commandControl.getContext(), true);
        
		requestMonitor.done();
	}

	/**
	 * This method shuts down this service. It unregisters the service, stops
	 * receiving service events, and calls the superclass shutdown() method to
	 * finish the shutdown process.
	 * 
	 * @return void
	 */
	@Override
	public void shutdown(RequestMonitor requestMonitor) {
		unregister();
		varManager.dispose();
		getSession().removeServiceEventListener(this);
		super.shutdown(requestMonitor);
	}
	
	/**
	 * @return The bundle context of the plug-in to which this service belongs.
	 */
	@Override
	protected BundleContext getBundleContext() {
		return MIPlugin.getBundleContext();
	}
	
	/**
	 * Create an expression context with the same full and relative expression
	 */
	public IExpressionDMContext createExpression(IDMContext ctx, String expression) {
		return createExpression(ctx, expression, expression);
	}

	/**
	 * Create an expression context.
	 */
	public IExpressionDMContext createExpression(IDMContext ctx, String expression, String relExpr) {
	    IFrameDMContext frameDmc = DMContexts.getAncestorOfType(ctx, IFrameDMContext.class);
	    if (frameDmc != null) {
	        return new MIExpressionDMC(getSession().getId(), expression, relExpr, frameDmc);
	    } 
	    
	    IMIExecutionDMContext execCtx = DMContexts.getAncestorOfType(ctx, IMIExecutionDMContext.class);
	    if (execCtx != null) {
	    	// If we have a thread context but not a frame context, we give the user
	    	// the expression as per the top-most frame of the specified thread.
	    	// To do this, we create our own frame context.
	    	MIStack stackService = getServicesTracker().getService(MIStack.class);
	    	if (stackService != null) {
	    		frameDmc = stackService.createFrameDMContext(execCtx, 0);
	            return new MIExpressionDMC(getSession().getId(), expression, relExpr, frameDmc);
	    	}

            return new InvalidContextExpressionDMC(getSession().getId(), expression, execCtx);
        } 
	    
        IMemoryDMContext memoryCtx = DMContexts.getAncestorOfType(ctx, IMemoryDMContext.class);
        if (memoryCtx != null) {
            return new MIExpressionDMC(getSession().getId(), expression, relExpr, memoryCtx);
        } 
        
        // Don't care about the relative expression at this point
        return new InvalidContextExpressionDMC(getSession().getId(), expression, ctx);
	}

	/**
	 * @see IFormattedValues.getFormattedValueContext(IFormattedDataDMContext, String)
	 * 
	 * @param dmc
	 *            The context describing the data for which we want to create
	 *            a Formatted context.
	 * @param formatId
	 *            The format that will be used to create the Formatted context
	 *            
	 * @return A FormattedValueDMContext that can be used to obtain the value
	 *         of an expression in a specific format. 
	 */

	public FormattedValueDMContext getFormattedValueContext(
			IFormattedDataDMContext dmc, String formatId) {
		return new FormattedValueDMContext(this, dmc, formatId);
	}

	/**
	 * @see IFormattedValues.getAvailableFormats(IFormattedDataDMContext, DataRequestMonitor)
	 * 
	 * @param dmc
	 *            The context describing the data for which we want to know
	 *            which formats are available.
	 * @param rm
	 *            The data request monitor for this asynchronous operation. 
	 *  
	 */

	public void getAvailableFormats(IFormattedDataDMContext dmc,
			final DataRequestMonitor<String[]> rm) {
		rm.setData(new String[] { IFormattedValues.BINARY_FORMAT,
				IFormattedValues.NATURAL_FORMAT, IFormattedValues.HEX_FORMAT,
				IFormattedValues.OCTAL_FORMAT, IFormattedValues.DECIMAL_FORMAT });
		rm.done();
	}
	
	/**
	 * This method obtains the model data for a given ExpressionDMC object or
	 * for a FormattedValueDMC, or for this DSF service.
	 * 
	 * @param dmc
	 *            The context for which we are requesting the data
	 * @param rm
	 *            The request monitor that will contain the requested data
	 */
	@SuppressWarnings("unchecked")
	public void getModelData(IDMContext dmc, DataRequestMonitor<?> rm) {
		if (dmc instanceof MIExpressionDMC) {
			getExpressionData((MIExpressionDMC) dmc,
					(DataRequestMonitor<IExpressionDMData>) rm);
		} else if (dmc instanceof FormattedValueDMContext) {
			getFormattedExpressionValue((FormattedValueDMContext) dmc,
					(DataRequestMonitor<FormattedValueDMData>) rm);
		} else {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "Unknown DMC type", null)); //$NON-NLS-1$
            rm.done();
		}
	}

	/**
	 * Obtains the static data of an expression represented 
	 * by an ExpressionDMC object (<tt>dmc</tt>).
	 * 
	 * @param dmc
	 *            The ExpressionDMC for the expression to be evaluated.
	 * @param rm
	 *            The data request monitor that will contain the requested data
	 */
	public void getExpressionData(
			final IExpressionDMContext dmc,
			final DataRequestMonitor<IExpressionDMData> rm) 
	{
	    if (dmc instanceof MIExpressionDMC) {
    		fExpressionCache.execute(
    				new ExprMetaGetVar(dmc), 
    				new DataRequestMonitor<ExprMetaGetVarInfo>(getExecutor(), rm) {
    					@Override
    					protected void handleSuccess() {
    						rm.setData(new ExpressionDMData(getData().getExpr(), 
    								getData().getType(), getData().getNumChildren(), getData().getEditable()));
    						rm.done();
    					}
    				});
	    } else if (dmc instanceof InvalidContextExpressionDMC) {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "Invalid context for evaluating expressions.", null)); //$NON-NLS-1$
            rm.done();
	    } else {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INTERNAL_ERROR, "Invalid expression context.", null)); //$NON-NLS-1$
            rm.done();
	    }
	}
	
	/**
	 * Obtains the address of an expression and the size of its type. 
	 * 
	 * @param dmc
	 *            The ExpressionDMC for the expression.
	 * @param rm
	 *            The data request monitor that will contain the requested data
	 */
    public void getExpressionAddressData(
    		IExpressionDMContext dmc, 
    		final DataRequestMonitor<IExpressionDMAddress> rm) {
        
    	// First create an address expression and a size expression
    	// to be used in back-end calls
    	final IExpressionDMContext addressDmc = 
    	    createExpression( dmc, "&(" + dmc.getExpression() + ")" );//$NON-NLS-1$//$NON-NLS-2$
    	final IExpressionDMContext sizeDmc = 
    	    createExpression( dmc, "sizeof(" + dmc.getExpression() + ")" ); //$NON-NLS-1$//$NON-NLS-2$

    	if (addressDmc instanceof InvalidContextExpressionDMC || sizeDmc instanceof InvalidContextExpressionDMC) {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "Invalid context for evaluating expressions.", null)); //$NON-NLS-1$
            rm.done();
    	} else {
        	fExpressionCache.execute(
    			new MIDataEvaluateExpression<MIDataEvaluateExpressionInfo>(addressDmc), 
    			new DataRequestMonitor<MIDataEvaluateExpressionInfo>(getExecutor(), rm) {
    				@Override
    				protected void handleSuccess() {
    					String tmpAddrStr = getData().getValue();
    					
    					// Deal with adresses of contents of a char* which is in
    					// the form of "0x12345678 \"This is a string\""
    					int split = tmpAddrStr.indexOf(' '); 
    			    	if (split != -1) tmpAddrStr = tmpAddrStr.substring(0, split);
    			    	final String addrStr = tmpAddrStr;
    			    	
    					fExpressionCache.execute(
    						new MIDataEvaluateExpression<MIDataEvaluateExpressionInfo>(sizeDmc), 
    						new DataRequestMonitor<MIDataEvaluateExpressionInfo>(getExecutor(), rm) {
    							@Override
    							protected void handleSuccess() {
    								try {
    									int size = Integer.parseInt(getData().getValue());
    									rm.setData(new ExpressionDMAddress(addrStr, size));
    								} catch (NumberFormatException e) {
    						            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE,
    						            		"Unexpected size format from backend: " + getData().getValue(), null)); //$NON-NLS-1$
    								}
    								rm.done();
    							}
    						});
    				}
    			});
    	}
	}

	/**
	 * Obtains the value of an expression in a specific format.
	 * 
	 * @param dmc
	 *            The context for the format of the value requested and 
	 *            for the expression to be evaluated.  The expression context
	 *            should be a parent of the FormattedValueDMContext.
	 * @param rm
	 *            The data request monitor that will contain the requested data
	 */
	public void getFormattedExpressionValue(
			final FormattedValueDMContext dmc,
			final DataRequestMonitor<FormattedValueDMData> rm) 
	{
		// We need to make sure the FormattedValueDMContext also holds an ExpressionContext,
		// or else this method cannot do its work.
		// Note that we look for MIExpressionDMC and not IExpressionDMC, because getting
		// looking for IExpressionDMC could yield InvalidContextExpressionDMC which is still
		// not what we need to have.
        if (DMContexts.getAncestorOfType(dmc, MIExpressionDMC.class) == null ) {
        	rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "Invalid context for evaluating expressions.", null)); //$NON-NLS-1$
        	rm.done();
        } else {
        	fExpressionCache.execute(
        			new ExprMetaGetValue(dmc),
        			new DataRequestMonitor<ExprMetaGetValueInfo>(getExecutor(), rm) {
        				@Override
        				protected void handleSuccess() {
        					rm.setData(new FormattedValueDMData(getData().getValue()));
        					rm.done();
        				}
        			});
        }
	}

	/* Not implemented
	 * 
	 * (non-Javadoc)
	 * @see org.eclipse.dd.dsf.debug.service.IExpressions#getBaseExpressions(org.eclipse.dd.dsf.debug.service.IExpressions.IExpressionDMContext, org.eclipse.dd.dsf.concurrent.DataRequestMonitor)
	 */
	public void getBaseExpressions(IExpressionDMContext exprContext,
			DataRequestMonitor<IExpressionDMContext[]> rm) {
		rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID,
				NOT_SUPPORTED, "Not supported", null)); //$NON-NLS-1$
		rm.done();
	}

	/**
	 * Retrieves the children expressions of the specified expression
	 * 
	 * @param exprCtx
	 *            The context for the expression for which the children
	 *            should be retrieved.
	 * @param rm
	 *            The data request monitor that will contain the requested data
	 */
	public void getSubExpressions(final IExpressionDMContext dmc,
			final DataRequestMonitor<IExpressionDMContext[]> rm) 
	{		
		if (dmc instanceof MIExpressionDMC) {
			fExpressionCache.execute(
					new ExprMetaGetChildren(dmc),				
					new DataRequestMonitor<ExprMetaGetChildrenInfo>(getExecutor(), rm) {
						@Override
						protected void handleSuccess() {
							ExpressionInfo[] childrenExpr = getData().getChildrenExpressions();
							IExpressionDMContext[] childArray = new IExpressionDMContext[childrenExpr.length];
							for (int i=0; i<childArray.length; i++) {
								childArray[i] = createExpression(
										dmc.getParents()[0], 
										childrenExpr[i].getFullExpr(),
										childrenExpr[i].getRelExpr());
							}

							rm.setData(childArray);
							rm.done();
						}
					});
		} else if (dmc instanceof InvalidContextExpressionDMC) {
			rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "Invalid context for evaluating expressions.", null)); //$NON-NLS-1$
			rm.done();
		} else {
			rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INTERNAL_ERROR, "Invalid expression context.", null)); //$NON-NLS-1$
			rm.done();
		}
	}

	/**
	 * Retrieves a range of children expressions of the specified expression
	 * 
	 * @param exprCtx
	 *            The context for the expression for which the children
	 *            should be retrieved.
	 * @param startIndex
	 *            The starting index within the list of all children of the parent
	 *            expression.
	 * @param length
	 *            The length or number of elements of the range requested
	 * @param rm
	 *            The data request monitor that will contain the requested data
	 */
	public void getSubExpressions(IExpressionDMContext exprCtx, final int startIndex,
			final int length, final DataRequestMonitor<IExpressionDMContext[]> rm) {
		getSubExpressions(
				exprCtx,
				new DataRequestMonitor<IExpressionDMContext[]>(getExecutor(), rm) {
					@Override
					protected void handleSuccess() {
						rm.setData((IExpressionDMContext[])Arrays.asList(getData()).subList(startIndex, startIndex + length).toArray());
						rm.done();
					}
				});
	}
	
	/**
	 * Retrieves the count of children expressions of the specified expression
	 * 
	 * @param exprCtx
	 *            The context for the expression for which the children count
	 *            should be retrieved.
	 * @param rm
	 *            The data request monitor that will contain the requested data
	 */
	public void getSubExpressionCount(IExpressionDMContext dmc,
			final DataRequestMonitor<Integer> rm) 
	{
		if (dmc instanceof MIExpressionDMC) {
			fExpressionCache.execute(
					new ExprMetaGetChildCount(dmc),				
					new DataRequestMonitor<ExprMetaGetChildCountInfo>(getExecutor(), rm) {
						@Override
						protected void handleSuccess() {
							rm.setData(getData().getChildNum());
							rm.done();
						}
					});
		} else if (dmc instanceof InvalidContextExpressionDMC) {
			rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "Invalid context for evaluating expressions.", null)); //$NON-NLS-1$
			rm.done();
		} else {
			rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INTERNAL_ERROR, "Invalid expression context.", null)); //$NON-NLS-1$
			rm.done();
		}
	}
	
    /**
     * This method indicates if an expression can be written to.
     * 
     * @param dmc: The data model context representing an expression.
     *
     * @param rm: Data Request monitor containing True if this expression's value can be edited.  False otherwise.
     */

	public void canWriteExpression(IExpressionDMContext dmc, final DataRequestMonitor<Boolean> rm) {
        if (dmc instanceof MIExpressionDMC) {
            fExpressionCache.execute(
                    new ExprMetaGetAttributes(dmc),
                    new DataRequestMonitor<ExprMetaGetAttributesInfo>(getExecutor(), rm) {
                        @Override
                        protected void handleSuccess() {
                            rm.setData(getData().getEditable());
                            rm.done();
                        }
                    });
        } else if (dmc instanceof InvalidContextExpressionDMC) {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "Invalid context for evaluating expressions.", null)); //$NON-NLS-1$
            rm.done();
        } else {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INTERNAL_ERROR, "Invalid expression context.", null)); //$NON-NLS-1$
            rm.done();
        }
	}


	/**
	 * Changes the value of the specified expression based on the new value and format.
	 * 
	 * @param expressionContext
	 *            The context for the expression for which the value 
	 *            should be changed.
	 * @param expressionValue
	 *            The new value for the specified expression
	 * @param formatId
	 *            The format in which the value is specified
	 * @param rm
	 *            The request monitor that will indicate the completion of the operation
	 */
	public void writeExpression(final IExpressionDMContext dmc, String expressionValue, 
			String formatId, final RequestMonitor rm) {

		if (dmc instanceof MIExpressionDMC) {
			// This command must not be cached, since it changes the state of the back-end.
			// We must send it directly to the variable manager
			varManager.writeValue(
			        dmc, 
					expressionValue, 
					formatId, 
					new RequestMonitor(getExecutor(), rm) {
						@Override
						protected void handleSuccess() {
							// A value has changed, we should remove any references to that
							// value in our cache.  Since we don't have such granularity,
							// we must clear the entire cache.
							// We cannot use the context to do a more-specific reset, because
							// the same global variable can be set with different contexts
							fExpressionCache.reset();
							
							// Issue event that the expression has changed
							getSession().dispatchEvent(new ExpressionChangedEvent(dmc), getProperties());
							
							rm.done();
						}
					});
		} else if (dmc instanceof InvalidContextExpressionDMC) {
			rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "Invalid context for evaluating expressions.", null)); //$NON-NLS-1$
			rm.done();
		} else {
			rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INTERNAL_ERROR, "Invalid expression context.", null)); //$NON-NLS-1$
			rm.done();
		}
	}

    @DsfServiceEventHandler 
    public void eventDispatched(IRunControl.IResumedDMEvent e) {
        fExpressionCache.setContextAvailable(e.getDMContext(), false);
        if (e.getReason() != StateChangeReason.STEP) {
            fExpressionCache.reset();
        }
    }
    
    @DsfServiceEventHandler 
    public void eventDispatched(IRunControl.ISuspendedDMEvent e) {
        fExpressionCache.setContextAvailable(e.getDMContext(), true);
        fExpressionCache.reset();
    }

    @DsfServiceEventHandler 
    public void eventDispatched(IMemoryChangedEvent e) {
        fExpressionCache.reset();
        // MIVariableManager separately traps this event
    }

    /**
     * {@inheritDoc}
     * @since 1.1
     */
    public void flushCache(IDMContext context) {
        fExpressionCache.reset(context);
        // We must also mark all variable objects as out-of-date
        // to refresh them as well
        varManager.markAllOutOfDate();
    }

}
