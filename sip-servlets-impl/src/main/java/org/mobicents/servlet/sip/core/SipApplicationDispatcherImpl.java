/**
 * 
 */
package org.mobicents.servlet.sip.core;

import gov.nist.javax.sip.stack.SIPServerTransaction;

import java.io.IOException;
import java.io.Serializable;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.sip.SipApplicationRouter;
import javax.servlet.sip.SipApplicationRouterInfo;
import javax.servlet.sip.SipApplicationRoutingDirective;
import javax.servlet.sip.SipApplicationRoutingRegion;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipRouteModifier;
import javax.servlet.sip.SipServlet;
import javax.servlet.sip.SipServletContextEvent;
import javax.servlet.sip.SipServletListener;
import javax.servlet.sip.SipURI;
import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.DialogState;
import javax.sip.DialogTerminatedEvent;
import javax.sip.IOExceptionEvent;
import javax.sip.InvalidArgumentException;
import javax.sip.ListeningPoint;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipException;
import javax.sip.SipProvider;
import javax.sip.TimeoutEvent;
import javax.sip.Transaction;
import javax.sip.TransactionAlreadyExistsException;
import javax.sip.TransactionTerminatedEvent;
import javax.sip.TransactionUnavailableException;
import javax.sip.address.Address;
import javax.sip.address.URI;
import javax.sip.header.CallIdHeader;
import javax.sip.header.ContentTypeHeader;
import javax.sip.header.Header;
import javax.sip.header.MaxForwardsHeader;
import javax.sip.header.RecordRouteHeader;
import javax.sip.header.RouteHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;

import org.apache.catalina.Container;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Wrapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.mobicents.servlet.sip.JainSipUtils;
import org.mobicents.servlet.sip.SipFactories;
import org.mobicents.servlet.sip.address.SipURIImpl;
import org.mobicents.servlet.sip.address.TelURLImpl;
import org.mobicents.servlet.sip.core.session.SessionManager;
import org.mobicents.servlet.sip.core.session.SipApplicationSessionImpl;
import org.mobicents.servlet.sip.core.session.SipApplicationSessionKey;
import org.mobicents.servlet.sip.core.session.SipListenersHolder;
import org.mobicents.servlet.sip.core.session.SipSessionImpl;
import org.mobicents.servlet.sip.core.session.SipSessionKey;
import org.mobicents.servlet.sip.message.SipFactoryImpl;
import org.mobicents.servlet.sip.message.SipServletRequestImpl;
import org.mobicents.servlet.sip.message.SipServletResponseImpl;
import org.mobicents.servlet.sip.message.TransactionApplicationData;
import org.mobicents.servlet.sip.proxy.ProxyBranchImpl;
import org.mobicents.servlet.sip.startup.SipContext;

/**
 * Implementation of the SipApplicationDispatcher interface.
 * Central point getting the sip messages from the different stacks for a Tomcat Service(Engine), 
 * translating jain sip SIP messages to sip servlets SIP�messages, calling the application router 
 * to know which app is interested in the messages and
 * dispatching them those sip applications interested in the messages. 
 */
public class SipApplicationDispatcherImpl implements SipApplicationDispatcher {
	//the logger
	private static transient Log logger = LogFactory
			.getLog(SipApplicationDispatcherImpl.class);
	
	public static final String ROUTE_PARAM_DIRECTIVE = "directive";
	
	public static final String ROUTE_PARAM_PREV_APPLICATION_NAME = "previousappname";
	/* 
	 * This parameter is to know which app handled the request 
	 */
	public static final String RR_PARAM_APPLICATION_NAME = "appname";
	/* 
	 * This parameter is to know which servlet handled the request 
	 */
	public static final String RR_PARAM_HANDLER_NAME = "handler";
	/* 
	 * This parameter is to know when an app was not deployed and couldn't handle the request
	 * used so that the response doesn't try to call the app not deployed
	 */
	public static final String APP_NOT_DEPLOYED = "appnotdeployed";
	
	private static Set<String> nonInitialSipRequestMethods = new HashSet<String>();
	
	static {
		nonInitialSipRequestMethods.add("CANCEL");
		nonInitialSipRequestMethods.add("BYE");
		nonInitialSipRequestMethods.add("PRACK");
		nonInitialSipRequestMethods.add("ACK");
	};
	
//	private enum InitialRequestRouting {
//		CONTINUE, STOP;
//	}
	
	//the sip factory implementation, it is not clear if the sip factory should be the same instance
	//for all applications
	private SipFactoryImpl sipFactoryImpl = null;
	//the sip application router responsible for the routing logic of sip messages to
	//sip servlet applications
	private SipApplicationRouter sipApplicationRouter = null;
	//map of applications deployed
	private Map<String, SipContext> applicationDeployed = null;
	//List of host names managed by the container
	private List<String> hostNames = null;
	
	//application chains
//	private Map<String, SipContext> applicationChains = null;
	
	private SessionManager sessionManager = null;
	
	private Boolean started = false;
	
	/**
	 * 
	 */
	public SipApplicationDispatcherImpl() {
		applicationDeployed = Collections.synchronizedMap(new HashMap<String, SipContext>());
		sessionManager = new SessionManager();
		sipFactoryImpl = new SipFactoryImpl(sessionManager);
		hostNames = Collections.synchronizedList(new ArrayList<String>());
	}
	
	/**
	 * {@inheritDoc} 
	 */
	public void init(String sipApplicationRouterClassName) throws LifecycleException {
		//load the sip application router from the class name specified in the server.xml file
		//and initializes it
		try {
			sipApplicationRouter = (SipApplicationRouter)
				Class.forName(sipApplicationRouterClassName).newInstance();
		} catch (InstantiationException e) {
			throw new LifecycleException("Impossible to load the Sip Application Router",e);
		} catch (IllegalAccessException e) {
			throw new LifecycleException("Impossible to load the Sip Application Router",e);
		} catch (ClassNotFoundException e) {
			throw new LifecycleException("Impossible to load the Sip Application Router",e);
		} catch (ClassCastException e) {
			throw new LifecycleException("Sip Application Router defined does not implement " + SipApplicationRouter.class.getName(),e);
		}		
		sipApplicationRouter.init(new ArrayList<String>(applicationDeployed.keySet()));		
	}
	/**
	 * {@inheritDoc}
	 */
	public void start() {
		synchronized (started) {
			started = true;
		}
		//JSR 289 Section 2.1.1 Step 4.If present invoke SipServletListener.servletInitialized() on each of initialized Servlet's listeners.
		for (SipContext sipContext : applicationDeployed.values()) {
			notifySipServletsListeners(sipContext);
		}		
	}
	
	/**
	 * Notifies the sip servlet listeners that the servlet has been initialized
	 * and that it is ready for service
	 * @param sipContext the sip context of the application where the listeners reside.
	 * @return true if all listeners have been notified correctly
	 */
	private boolean notifySipServletsListeners(SipContext sipContext) {
		boolean ok = true;
		SipListenersHolder sipListenersHolder = sipContext.getListeners();
		List<SipServletListener> sipServletListeners = sipListenersHolder.getSipServletsListeners();
		Container[] children = sipContext.findChildren();	
		for (Container container : children) {
			if(container instanceof Wrapper) {			
				Wrapper wrapper = (Wrapper) container;
				try {
					Servlet sipServlet = wrapper.allocate();
					if(sipServlet instanceof SipServlet) {
						SipServletContextEvent sipServletContextEvent = 
							new SipServletContextEvent(sipContext.getServletContext(), (SipServlet)sipServlet);
						for (SipServletListener sipServletListener : sipServletListeners) {					
							sipServletListener.servletInitialized(sipServletContextEvent);					
						}
					}
				} catch (ServletException e) {
					logger.error("Cannot allocate the servlet "+ wrapper.getServletClass() +" for notifying the listener " +
							"that it has been initialized", e);
					ok = false; 
				} catch (Throwable e) {
					logger.error("An error occured when initializing the servlet " + wrapper.getServletClass(), e);
					ok = false; 
				}					
			}
		}			
		return ok;
	}
	
	/**
	 * {@inheritDoc}
	 */
	public void stop() {
		sipApplicationRouter.destroy();		
	}
	
	/**
	 * {@inheritDoc}
	 */
	public void addSipApplication(String sipApplicationName, SipContext sipApplication) {
		applicationDeployed.put(sipApplicationName, sipApplication);
		List<String> newlyApplicationsDeployed = new ArrayList<String>();
		newlyApplicationsDeployed.add(sipApplicationName);
		sipApplicationRouter.applicationDeployed(newlyApplicationsDeployed);
		//if the ApplicationDispatcher is started, notification is sent that the servlets are ready for service
		//otherwise the notification will be delayed until the ApplicationDispatcher has started
		synchronized (started) {
			if(started) {
				notifySipServletsListeners(sipApplication);
			}
		}
		logger.info("the following sip servlet application has been added : " + sipApplicationName);
	}
	/**
	 * {@inheritDoc}
	 */
	public SipContext removeSipApplication(String sipApplicationName) {
		SipContext sipContext = applicationDeployed.remove(sipApplicationName);
		List<String> applicationsUndeployed = new ArrayList<String>();
		applicationsUndeployed.add(sipApplicationName);
		sipApplicationRouter.applicationUndeployed(applicationsUndeployed);
		logger.info("the following sip servlet application has been removed : " + sipApplicationName);
		return sipContext;
	}
	/**
	 * {@inheritDoc}
	 */	
	public void processDialogTerminated(DialogTerminatedEvent dialogTerminatedEvent) {
		// TODO FIXME
		logger.info("Dialog Terminated => " + dialogTerminatedEvent.getDialog().getCallId().getCallId());
		Dialog dialog = dialogTerminatedEvent.getDialog();		
		TransactionApplicationData tad = (TransactionApplicationData) dialog.getApplicationData();
		tad.getSipServletMessage().getSipSession().onDialogTimeout(dialog);
		sessionManager.removeSipSession(tad.getSipServletMessage().getSipSession().getKey());
	}
	/**
	 * {@inheritDoc}
	 */
	public void processIOException(IOExceptionEvent arg0) {
		// TODO Auto-generated method stub
		
	}
	/**
	 * {@inheritDoc}
	 */
	public void processRequest(RequestEvent requestEvent) {		
		SipProvider sipProvider = (SipProvider)requestEvent.getSource();
		ServerTransaction transaction =  requestEvent.getServerTransaction();
		Request request = requestEvent.getRequest();
		try {
			logger.info("Got a request event "  + request.getMethod());
			
			if ( transaction == null ) {
				try {
					transaction = sipProvider.getNewServerTransaction(request);
				} catch ( TransactionUnavailableException tae) {
					// Sends a 500 Internal server error and stops processing.				
					JainSipUtils.sendErrorResponse(Response.SERVER_INTERNAL_ERROR, transaction, request, sipProvider);				
	                return;
				} catch ( TransactionAlreadyExistsException taex ) {
					// Already processed this request so just return.
					return;				
				} 
			}									
			
			SipServletRequestImpl sipServletRequest = new SipServletRequestImpl(
						request,
						sipFactoryImpl,
						null,
						transaction,
						null,true);						
			
			// Check if the request is meant for me. If so, strip the topmost
			// Route header.
			RouteHeader routeHeader = (RouteHeader) request
					.getHeader(RouteHeader.NAME);
			//Popping the router header if it's for the container as
			//specified in JSR 289 - Section 15.8
			if(! isRouteExternal(routeHeader)) {
				request.removeFirst(RouteHeader.NAME);
				sipServletRequest.setPoppedRoute(routeHeader);
			}							
						
			//check if the request is initial
			RoutingState routingState = checkRoutingState(sipServletRequest, requestEvent.getDialog());				
			sipServletRequest.setRoutingState(routingState);					
			logger.info("Routing State " + routingState);			
										
			if(sipServletRequest.isInitial()) {
				logger.info("Routing of Initial Request " + request);
				routeInitialRequest(sipProvider, sipServletRequest);							
			} else {
				logger.info("Routing of Subsequent Request " + request);
				Dialog dialog = sipServletRequest.getDialog();				
				if(sipServletRequest.getMethod().equals(Request.CANCEL)) {
					routeCancel(sipProvider, sipServletRequest, dialog);
				} else {
					routeSubsequentRequest(sipProvider, sipServletRequest, dialog);
				}
			}
		} catch (Throwable e) {
			e.printStackTrace();
			logger.error(e);
			// Sends a 500 Internal server error and stops processing.				
			JainSipUtils.sendErrorResponse(Response.SERVER_INTERNAL_ERROR, transaction, request, sipProvider);
			return;
		}
	}

	/**
	 * Forward statefully a request whether it is initial or subsequent
	 * and keep track of the transactions used in application data of each transaction
	 * @param sipProvider the sip provider used to get a new client Transaction
	 * @param serverTransaction the server transaction on which we received the request
	 * @param sipServletRequest the sip servlet request to forward statefully
	 * @throws ParseException 
	 * @throws TransactionUnavailableException
	 * @throws SipException
	 * @throws InvalidArgumentException 
	 */
	private void forwardStatefully(SipProvider sipProvider,
			ServerTransaction serverTransaction,
			SipServletRequestImpl sipServletRequest,
			Dialog dialog) throws ParseException,
			TransactionUnavailableException, SipException, InvalidArgumentException {
				
		Request clonedRequest = (Request)sipServletRequest.getMessage().clone();		
		
		//Add via header
		String transport = JainSipUtils.findTransport(clonedRequest);		
		ViaHeader viaHeader = JainSipUtils.createViaHeader(
				sipFactoryImpl.getSipProviders(), transport, null);
		String handlerName = sipServletRequest.getSipSession().getHandler();
		
		if(handlerName != null) { 
			viaHeader.setParameter(SipApplicationDispatcherImpl.RR_PARAM_APPLICATION_NAME,
					sipServletRequest.getSipSession().getKey().getApplicationName());
			viaHeader.setParameter(SipApplicationDispatcherImpl.RR_PARAM_HANDLER_NAME,
					sipServletRequest.getSipSession().getHandler());
		} else {
			// if the handler name is null it means that the app returned by the AR was not deployed
			// and couldn't be called, 
			// we specify it so that on response handling this app can be skipped
			viaHeader.setParameter(SipApplicationDispatcherImpl.APP_NOT_DEPLOYED,
					sipServletRequest.getSipSession().getKey().getApplicationName());						
		}					
		clonedRequest.addHeader(viaHeader);
		//decrease the Max Forward Header
		MaxForwardsHeader mf = (MaxForwardsHeader) clonedRequest
			.getHeader(MaxForwardsHeader.NAME);
		if (mf == null) {
			mf = SipFactories.headerFactory.createMaxForwardsHeader(70);
			clonedRequest.addHeader(mf);
		} else {
			mf.setMaxForwards(mf.getMaxForwards() - 1);
		}
		if(logger.isDebugEnabled()) {
			logger.debug("Routing Back to the container the following request " 
					+ clonedRequest);
		}				
		if(dialog == null) {
			Transaction transaction = ((TransactionApplicationData)
					sipServletRequest.getTransaction().getApplicationData()).getSipServletMessage().getTransaction();
			if(transaction == null || transaction instanceof ServerTransaction) {
				ClientTransaction ctx = sipProvider.getNewClientTransaction(clonedRequest);
				//keeping the server transaction in the client transaction's application data
				TransactionApplicationData appData = new TransactionApplicationData(sipServletRequest);					
				appData.setTransaction(serverTransaction);
				ctx.setApplicationData(appData);
				//keeping the client transaction in the server transaction's application data
				((TransactionApplicationData)serverTransaction.getApplicationData()).setTransaction(ctx);
				ctx.sendRequest();
			} else {
				((ClientTransaction)transaction).sendRequest();
			}
		} else if ( clonedRequest.getMethod().equals("ACK") ) {
            dialog.sendAck(clonedRequest);
		} else {
			Request dialogRequest=
				dialog.createRequest(clonedRequest.getMethod());
	        Object content=clonedRequest.getContent();
	        if (content!=null) {
	        	ContentTypeHeader contentTypeHeader= (ContentTypeHeader)
	        		clonedRequest.getHeader(ContentTypeHeader.NAME);
	            if (contentTypeHeader!=null) {
	            	dialogRequest.setContent(content,contentTypeHeader);
	        	}
	                     
	            // Copy all the headers from the original request to the 
	            // dialog created request:	            
                ListIterator l=clonedRequest.getHeaderNames();
                while (l.hasNext() ) {
                     String name=(String)l.next();
                     Header header=dialogRequest.getHeader(name);
                     if (header==null  ) {
                        ListIterator li=clonedRequest.getHeaders(name);
                        if (li!=null) {
                            while (li.hasNext() ) {
                                Header  h=(Header)li.next();
                                dialogRequest.addHeader(h);
                            }
                        }
                     }
                     else {
                         if ( header instanceof ViaHeader) {
                             ListIterator li= clonedRequest.getHeaders(name);
                             if (li!=null) {
                                 dialogRequest.removeHeader(name);
                                 Vector v=new Vector();
                                 while (li.hasNext() ) {
                                     Header  h=(Header)li.next();
                                     v.addElement(h);
                                 }
                                 for (int k=(v.size()-1);k>=0;k--) {
                                     Header  h=(Header)v.elementAt(k);
                                     dialogRequest.addHeader(h);
                                 }
                             }
                         }
                     }
                }       
	            	                    
	            ClientTransaction clientTransaction =
				sipProvider.getNewClientTransaction(dialogRequest);
	                    dialog.sendRequest(clientTransaction);
	        }
		}
	}

	/**
	 * @param sipProvider
	 * @param transaction
	 * @param request
	 * @param sipServletRequest
	 * @throws InvalidArgumentException 
	 * @throws SipException 
	 * @throws ParseException 
	 * @throws TransactionUnavailableException 
	 */
	private boolean routeSubsequentRequest(SipProvider sipProvider, SipServletRequestImpl sipServletRequest, Dialog dialog) throws TransactionUnavailableException, ParseException, SipException, InvalidArgumentException {
		ServerTransaction transaction = (ServerTransaction) sipServletRequest.getTransaction();
		Request request = (Request) sipServletRequest.getMessage();

		javax.servlet.sip.Address poppedAddress = sipServletRequest.getPoppedRoute();
				
		if(poppedAddress==null){
			throw new IllegalArgumentException("The popped route shouldn't be null for not proxied requests.");
		}
		//Extract information from the Record Route Header		
		String applicationName = poppedAddress.getParameter(RR_PARAM_APPLICATION_NAME);
		String handlerName = poppedAddress.getParameter(RR_PARAM_HANDLER_NAME);
		if(applicationName == null || applicationName.length() < 1 || 
				handlerName == null || handlerName.length() < 1) {
			throw new IllegalArgumentException("cannot find the application to handle this subsequent request " +
					"in this popped routed header " + poppedAddress);
		}
		boolean inverted = false;
		if(dialog != null && !dialog.isServer()) {
			inverted = true;
		}
		
		SipSessionKey key = SessionManager.getSipSessionKey(applicationName, sipServletRequest.getMessage(), inverted);
		SipSessionImpl sipSession = sessionManager.getSipSession(key, false, sipFactoryImpl);
		
		// Added by Vladimir because the inversion detection on proxied requests doesn't work
		if(sipSession == null) {
			key = SessionManager.getSipSessionKey(applicationName, sipServletRequest.getMessage(), !inverted);
			sipSession = sessionManager.getSipSession(key, false, sipFactoryImpl);
		}
		
		if(sipSession == null) {			
			logger.error("Cannot find the corresponding sip session to this subsequent request " + request +
					" with the following popped route header " + sipServletRequest.getPoppedRoute());
			sessionManager.dumpSipSessions();
			// Sends a 500 Internal server error and stops processing.				
			JainSipUtils.sendErrorResponse(Response.SERVER_INTERNAL_ERROR, transaction, request, sipProvider);
			return false;
		}
		SipApplicationSessionKey sipApplicationSessionKey = SessionManager.getSipApplicationSessionKey(
				applicationName, 
				((CallIdHeader)request.getHeader((CallIdHeader.NAME))).getCallId());
		SipApplicationSessionImpl sipApplicationSession = sessionManager.getSipApplicationSession(sipApplicationSessionKey, false);
		if(sipApplicationSession == null) {
			logger.error("Cannot find the corresponding sip application session to this subsequent request " + request +
					" with the following popped route header " + sipServletRequest.getPoppedRoute());
			sessionManager.dumpSipApplicationSessions();
			// Sends a 500 Internal server error and stops processing.				
			JainSipUtils.sendErrorResponse(Response.SERVER_INTERNAL_ERROR, transaction, request, sipProvider);
			return false;
		}
		sipSession.setSipApplicationSession(sipApplicationSession);
		sipServletRequest.setSipSession(sipSession);
					
		Wrapper servletWrapper = (Wrapper) applicationDeployed.get(applicationName).findChild(handlerName);
		try {
			
			// See if the response should go directly to the proxy
			if(sipServletRequest.getSipSession().getProxyBranch() != null)
			{
				ProxyBranchImpl proxyBranch = sipServletRequest.getSipSession().getProxyBranch();
				if(proxyBranch.getProxy().getSupervised())
				{
					Servlet servlet = servletWrapper.allocate();
					servlet.service(sipServletRequest, null);
				}
				proxyBranch.proxyInDialogRequest(sipServletRequest);
			}
			// If it's not for a proxy then it's just an AR, so go to the next application
			else
			{
				Servlet servlet = servletWrapper.allocate();
				servlet.service(sipServletRequest, null);
			}
		} catch (ServletException e) {				
			logger.error(e);
			// Sends a 500 Internal server error and stops processing.				
			JainSipUtils.sendErrorResponse(Response.SERVER_INTERNAL_ERROR, transaction, request, sipProvider);
			return false;
		} catch (IOException e) {				
			logger.error(e);
			// Sends a 500 Internal server error and stops processing.				
			JainSipUtils.sendErrorResponse(Response.SERVER_INTERNAL_ERROR, transaction, request, sipProvider);
			return false;
		}
		//if a final response has been sent, or if the request has 
		//been proxied or relayed we stop routing the request
		RoutingState routingState = sipServletRequest.getRoutingState();
		if(RoutingState.FINAL_RESPONSE_SENT.equals(routingState) ||
				RoutingState.PROXIED.equals(routingState) ||
				RoutingState.RELAYED.equals(routingState) ||
				RoutingState.CANCELLED.equals(routingState)) {
			if(logger.isDebugEnabled()) {
				logger.debug("Routing State : " + sipServletRequest.getRoutingState() +
						"The Container hence stops routing the initial request.");
			}
			return false;
		} else {		
			// Check if the request is meant for me. 
			RouteHeader routeHeader = (RouteHeader) request
					.getHeader(RouteHeader.NAME);
			if(routeHeader == null || isRouteExternal(routeHeader)) {
				// no route header or external, send outside the container
				// FIXME send it statefully
				try{
					sipProvider.sendRequest((Request)request.clone());						
					logger.info("Subsequent Request dispatched outside the container" + request.toString());
				} catch (Exception ex) {			
					throw new IllegalStateException("Error sending request",ex);
				}	
				return false;
			} else {		
				//route header is meant for the container hence we continue
				forwardStatefully(sipProvider, transaction,
						sipServletRequest, dialog);
				return true;
			}
		}		
	}

	/**
	 * @param sipProvider
	 * @param sipServletRequest
	 * @param transaction
	 * @param request
	 * @param poppedAddress
	 */
	private boolean routeCancel(SipProvider sipProvider, SipServletRequestImpl sipServletRequest, Dialog dialog) {
		
		ServerTransaction transaction = (ServerTransaction) sipServletRequest.getTransaction();
		Request request = (Request) sipServletRequest.getMessage();		
						
		/*
		 * WARNING: TODO: We need to find a way to route CANCELs through the app path
		 * of the INVITE. CANCEL does not contain Route headers as other requests related
		 * to the dialog.
		 */
		/* If there is a proxy with the request, let's try to send it directly there.
		 * This is needed because of CANCEL which is a subsequent request that might
		 * not have Routes. For example if the callee has'n responded the caller still
		 * doesn't know the route-record and just sends cancel to the outbound proxy.
		 */	
//		boolean proxyCancel = false;
		try {
			// First we need to send OK ASAP because of retransmissions both for 
			//proxy or app
			ServerTransaction cancelTransaction = 
				(ServerTransaction) sipServletRequest.getTransaction();
			SipServletResponseImpl cancelResponse = (SipServletResponseImpl) 
			sipServletRequest.createResponse(200, "Canceling");
			Response cancelJsipResponse = (Response) cancelResponse.getMessage();
			cancelTransaction.sendResponse(cancelJsipResponse);
		} catch (SipException e) {
			logger.error("Impossible to send the ok to the CANCEL",e);
			JainSipUtils.sendErrorResponse(Response.SERVER_INTERNAL_ERROR, transaction, request, sipProvider);
			return false;
		} catch (InvalidArgumentException e) {
			logger.error("Impossible to send the ok to the CANCEL",e);
			JainSipUtils.sendErrorResponse(Response.SERVER_INTERNAL_ERROR, transaction, request, sipProvider);
			return false;
		}
							
		Transaction inviteTransaction = ((SIPServerTransaction) sipServletRequest.getTransaction()).getCanceledInviteTransaction();
		TransactionApplicationData appData = (TransactionApplicationData) 
			inviteTransaction.getApplicationData();
		if(appData.getProxy() != null) {				
			appData.getProxy().cancel();
//			proxyCancel = true;
			return true;
		} else {
			SipServletRequestImpl inviteRequest = (SipServletRequestImpl)
				appData.getSipServletMessage();
			SipServletResponseImpl inviteResponse = (SipServletResponseImpl) 
				inviteRequest.createResponse(487);
			try {
				inviteRequest.setRoutingState(RoutingState.CANCELLED);
			} catch (IllegalStateException e) {
				logger.info("Final response already sent, dropping the cancel");
				return false;
			}
			try {
				Response requestTerminatedResponse = (Response) inviteResponse.getMessage();
					((ServerTransaction)inviteTransaction).sendResponse(requestTerminatedResponse);
				((SipServletRequestImpl)appData.getSipServletMessage()).setRoutingState(RoutingState.CANCELLED);
			} catch (SipException e) {
				logger.error("Impossible to send the 487 to the INVITE transaction corresponding to CANCEL",e);
				JainSipUtils.sendErrorResponse(Response.SERVER_INTERNAL_ERROR, transaction, request, sipProvider);
				return false;
			} catch (InvalidArgumentException e) {
				logger.error("Impossible to send the ok 487 to the INVITE transaction corresponding to CANCEL",e);
				JainSipUtils.sendErrorResponse(Response.SERVER_INTERNAL_ERROR, transaction, request, sipProvider);
				return false;
			}
			//Forwarding the cancel on the other B2BUA side
			if(inviteRequest.getLinkedRequest() != null) {					
				SipServletRequestImpl cancelRequest = (SipServletRequestImpl)
					inviteRequest.getLinkedRequest().createCancel();
//				cancelRequest.getMessage().removeFirst(RouteHeader.NAME);
//				cancelRequest.getMessage().removeFirst(RecordRouteHeader.NAME);				
				cancelRequest.send();
				return true;
			} else {
				SipSessionImpl sipSession = inviteRequest.getSipSession();
				sipServletRequest.setSipSession(sipSession);
				Wrapper servletWrapper = (Wrapper) applicationDeployed.get(sipSession.getKey().getApplicationName()).findChild(sipSession.getHandler());
				try{
					Servlet servlet = servletWrapper.allocate();
					servlet.service(sipServletRequest, null);
					return true;
				} catch (ServletException e) {				
					logger.error(e);
					// Sends a 500 Internal server error and stops processing.				
//						JainSipUtils.sendErrorResponse(Response.SERVER_INTERNAL_ERROR, transaction, request, sipProvider);
					return false;
				} catch (IOException e) {				
					logger.error(e);
					// Sends a 500 Internal server error and stops processing.				
//						JainSipUtils.sendErrorResponse(Response.SERVER_INTERNAL_ERROR, transaction, request, sipProvider);
					return false;
				}
			}
		}
		
		// TODO: This is a temporary patch. Rewrite when AR uses transactions
		// This code handles CANCEL requests that got back to the container
		// after being proxied for example.
//			if(poppedAddress != null)
//			{
//				try {
//					sipProvider.sendRequest((Request)sipServletRequest.getMessage());
//				} catch (SipException e) {
//					// TODO Auto-generated catch block
//					e.printStackTrace();
//				}
//			}
		// End of code to rewrite
//		if(!proxyCancel && poppedAddress==null){
//			throw new IllegalArgumentException("The popped route shouldn't be null for not proxied requests.");
//		}
	}

	/**
	 * 
	 * @param sipProvider
	 * @param transaction
	 * @param request
	 * @param session
	 * @param sipServletRequest
	 * @throws ParseException 
	 * @throws InvalidArgumentException 
	 * @throws SipException 
	 * @throws TransactionUnavailableException 
	 */
	private boolean routeInitialRequest(SipProvider sipProvider, SipServletRequestImpl sipServletRequest) throws ParseException, TransactionUnavailableException, SipException, InvalidArgumentException {
		//15.4.1 Routing an Initial request Algorithm
		ServerTransaction transaction = (ServerTransaction) sipServletRequest.getTransaction();
		Request request = (Request) sipServletRequest.getMessage();
		
		javax.servlet.sip.Address poppedAddress = sipServletRequest.getPoppedRoute();
		logger.info("popped route : " + poppedAddress);
		//set directive from popped route header if it is present			
		Serializable stateInfo = null;
		SipApplicationRoutingDirective sipApplicationRoutingDirective = SipApplicationRoutingDirective.NEW;;
		if(poppedAddress != null) {
			// get the state info associated with the request because it means 
			// that is has been routed back to the container			
			String directive = poppedAddress.getParameter(ROUTE_PARAM_DIRECTIVE);
			if(directive != null && directive.length() > 0) {
				logger.info("directive before the request has been routed back to container : " + directive);
				sipApplicationRoutingDirective = SipApplicationRoutingDirective.valueOf(
						SipApplicationRoutingDirective.class, directive);
				String previousAppName = poppedAddress.getParameter(ROUTE_PARAM_PREV_APPLICATION_NAME);
				logger.info("application name before the request has been routed back to container : " + previousAppName);		
				SipSessionKey sipSessionKey = SessionManager.getSipSessionKey(previousAppName, request, false);
				SipSessionImpl sipSession = sessionManager.getSipSession(sipSessionKey, false, sipFactoryImpl);
				stateInfo = sipSession.getStateInfo();
				sipServletRequest.setSipSession(sipSession);
				logger.info("state info before the request has been routed back to container : " + stateInfo);
			}
		} else if(sipServletRequest.getSipSession() != null) {
			stateInfo = sipServletRequest.getSipSession().getStateInfo();
			sipApplicationRoutingDirective = sipServletRequest.getRoutingDirective();
			logger.info("previous state info : " + stateInfo);
		}
					
		// 15.4.1 Procedure : point 1		
		SipApplicationRoutingRegion routingRegion = null;
		if(sipServletRequest.getSipSession() != null) {
			routingRegion = sipServletRequest.getSipSession().getRegion();
		}
		//TODO the spec mandates that the sipServletRequest should be 
		//made read only for the AR to process
		SipApplicationRouterInfo applicationRouterInfo = 
			sipApplicationRouter.getNextApplication(
					sipServletRequest, 
					routingRegion, 
					sipApplicationRoutingDirective, 
					stateInfo);
		// 15.4.1 Procedure : point 2
		SipRouteModifier sipRouteModifier = applicationRouterInfo.getRouteModifier();
		if(sipRouteModifier != null) {
			switch(sipRouteModifier) {
				// ROUTE modifier indicates that SipApplicationRouterInfo.getRoute() returns a valid route,
				// it is up to container to decide whether it is external or internal.
				case ROUTE :
					String route = applicationRouterInfo.getRoute();
					Address routeAddress = null; 
					RouteHeader applicationRouterInfoRouteHeader = null;
					try {
						routeAddress = SipFactories.addressFactory.createAddress(route);
						applicationRouterInfoRouteHeader = SipFactories.headerFactory.createRouteHeader(routeAddress);
					} catch (ParseException e) {
						logger.error("Impossible to parse the route returned by the application router " +
								"into a compliant address",e);							
						// the AR returned an empty string route or a bad route
						// this shouldn't happen if the route modifier is ROUTE, processing is stopped
						//and a 500 is sent
						JainSipUtils.sendErrorResponse(Response.SERVER_INTERNAL_ERROR, transaction, request, sipProvider);
						return false;
					}						
					if(isRouteExternal(applicationRouterInfoRouteHeader)) {
						// push the route as the top Route header field value and send the request externally
						sipServletRequest.addHeader(RouteHeader.NAME, route);
						sipServletRequest.send();
						return false;
					} else {
						// the container MUST make the route available to the applications 
						// via the SipServletRequest.getPoppedRoute() method.							
						sipServletRequest.setPoppedRoute(applicationRouterInfoRouteHeader);												
					}
					break;
				// NO_ROUTE indicates that application router is not returning any route 
				// and the SipApplicationRouterInfo.getRoute() value if any should be disregarded.	
				case CLEAR_ROUTE :
					//nothing to do here
					//disregard the result.getRoute() and proceed. Specifically the SipServletRequest.getPoppedRoute() 
					//returns the same value as before the invocation of application router.
					break;
				// CLEAR_ROUTE modifier indicates to the container to remove the popped top route associated with the request. 
				// Specifically the subsequent invocation of SipServletRequest.getPoppedRoute() MUST now return null
				case NO_ROUTE :
					// clear the value of popped route header such that SipServletRequest.getPoppedRoute() 
					//now returns null until another route header belonging to the container is popped.
					sipServletRequest.setPoppedRoute(null);
					break;				
			}
		}
		// 15.4.1 Procedure : point 3
		if(applicationRouterInfo.getNextApplicationName() == null) {
			logger.info("Dispatching the request event outside the container");
			//check if the request point to another domain
			
			javax.sip.address.SipURI sipRequestUri = (javax.sip.address.SipURI)request.getRequestURI();
			String host = sipRequestUri.getHost();
			int port = sipRequestUri.getPort();
			String transport = JainSipUtils.findTransport(request);
			boolean isAnotherDomain = isExternal(host, port, transport);			
			ListIterator<String> routeHeaders = sipServletRequest.getHeaders(RouteHeader.NAME);				
			if(isAnotherDomain || routeHeaders.hasNext()) {
				// FIXME send it statefully
				try{
					sipProvider.sendRequest((Request)request.clone());						
					logger.info("Subsequent Request dispatched outside the container" + request.toString());
				} catch (Exception ex) {			
					throw new IllegalStateException("Error sending request",ex);
				}	
				return false;
			} else {
				// the Request-URI does not point to another domain, and there is no Route header, 
				// the container should not send the request as it will cause a loop. 
				// Instead, the container must reject the request with 404 Not Found final response with no Retry-After header.					 			
				JainSipUtils.sendErrorResponse(Response.NOT_FOUND, transaction, request, sipProvider);
				return false;
			}
		} else {
			logger.info("Dispatching the request event to " + applicationRouterInfo.getNextApplicationName());
			sipServletRequest.setCurrentApplicationName(applicationRouterInfo.getNextApplicationName());
			//sip session association
			SipSessionKey sessionKey = SessionManager.getSipSessionKey(applicationRouterInfo.getNextApplicationName(), request, false);
			SipSessionImpl sipSession = sessionManager.getSipSession(sessionKey, true, sipFactoryImpl);
			sipSession.setSessionCreatingTransaction(transaction);
			sipServletRequest.setSipSession(sipSession);
			//sip appliation session association
			//TODO: later should check for SipApplicationKey annotated method in the servlet.
			SipApplicationSessionKey sipApplicationSessionKey = SessionManager.getSipApplicationSessionKey(
					applicationRouterInfo.getNextApplicationName(), 
					((CallIdHeader)request.getHeader((CallIdHeader.NAME))).getCallId());
			SipApplicationSessionImpl appSession = sessionManager.getSipApplicationSession(
					sipApplicationSessionKey, true);
			sipSession.setSipApplicationSession(appSession);			
			
			// set the request's stateInfo to result.getStateInfo(), region to result.getRegion(), and URI to result.getSubscriberURI().			
			sipServletRequest.getSipSession().setStateInfo(applicationRouterInfo.getStateInfo());
			sipServletRequest.getSipSession().setRoutingRegion(applicationRouterInfo.getRoutingRegion());
			try {
				URI subscriberUri = SipFactories.addressFactory.createURI(applicationRouterInfo.getSubscriberURI());				
				if(subscriberUri instanceof javax.sip.address.SipURI) {
					sipServletRequest.setRequestURI(new SipURIImpl((javax.sip.address.SipURI)subscriberUri));
				} else if (subscriberUri instanceof javax.sip.address.TelURL) {
					sipServletRequest.setRequestURI(new TelURLImpl((javax.sip.address.TelURL)subscriberUri));
				}
			} catch (ParseException pe) {					
				logger.error(pe);
				// Sends a 500 Internal server error and stops processing.				
				JainSipUtils.sendErrorResponse(Response.SERVER_INTERNAL_ERROR, transaction, request, sipProvider);
				return false;
			}
			// follow the procedures of Chapter 16 to select a servlet from the application.			
			SipContext sipContext = applicationDeployed.get(applicationRouterInfo.getNextApplicationName());
			//no matching deployed apps
			if(sipContext == null) {
				logger.error("No matching deployed application has been found !");
				// the app returned by the Application Router returned an app
				// that is not currently deployed we continue routing to see if 
				// other apps are interested
				sipServletRequest.addInfoForRoutingBackToContainer();
				forwardStatefully(sipProvider, transaction, sipServletRequest, null);
				return true;
			}
			appSession.setSipContext(sipContext);
			String sipSessionHandlerName = sipServletRequest.getSipSession().getHandler();						
			if(sipSessionHandlerName == null || sipSessionHandlerName.length() < 1) {
				String mainServlet = sipContext.getMainServlet();
				sipSessionHandlerName = mainServlet;					
				try {
					sipServletRequest.getSipSession().setHandler(sipSessionHandlerName);
				} catch (ServletException e) {
					// this should never happen
					logger.error(e);
					// Sends a 500 Internal server error and stops processing.				
					JainSipUtils.sendErrorResponse(Response.SERVER_INTERNAL_ERROR, transaction, request, sipProvider);
					return false;
				} 
			}
			Container container = sipContext.findChild(sipSessionHandlerName);
			Wrapper sipServletImpl = (Wrapper) container;
			try {
				Servlet servlet = sipServletImpl.allocate();	        
				servlet.service(sipServletRequest, null);
				logger.info("Request event dispatched to " + sipContext.getApplicationName());				
			} catch (ServletException e) {				
				logger.error(e);
				// Sends a 500 Internal server error and stops processing.				
				JainSipUtils.sendErrorResponse(Response.SERVER_INTERNAL_ERROR, transaction, request, sipProvider);
				return false;
			} catch (IOException e) {				
				logger.error(e);
				// Sends a 500 Internal server error and stops processing.				
				JainSipUtils.sendErrorResponse(Response.SERVER_INTERNAL_ERROR, transaction, request, sipProvider);
				return false;
			} 			
			//if a final response has been sent, or if the request has 
			//been proxied or relayed we stop routing the request
			RoutingState routingState = sipServletRequest.getRoutingState();			
			if(RoutingState.FINAL_RESPONSE_SENT.equals(routingState) ||
					RoutingState.PROXIED.equals(routingState) ||
					RoutingState.RELAYED.equals(routingState) ||
					RoutingState.CANCELLED.equals(routingState)) {
				if(logger.isDebugEnabled()) {
					logger.debug("Routing State : " + sipServletRequest.getRoutingState() +
							"The Container hence stops routing the initial request.");
				}
				return false;
			} else {
				if(logger.isDebugEnabled()) {
					logger.debug("Routing State : " + sipServletRequest.getRoutingState() +
							"The Container hence continue routing the initial request.");
				}
				try {
					// the app that was called didn't do anything with the request
					// in any case we should route back to container statefully 
					sipServletRequest.addAppCompositionRRHeader();
					sipServletRequest.addInfoForRoutingBackToContainer();
					forwardStatefully(sipProvider, transaction,
							sipServletRequest, null);
					return true;
				} catch (SipException e) {				
					logger.error(e);
					// Sends a 500 Internal server error and stops processing.				
					JainSipUtils.sendErrorResponse(Response.SERVER_INTERNAL_ERROR, transaction, request, sipProvider);
					return false;
				}
			}
		}		
	}
	/**
	 * Check if the route is external
	 * @param routeHeader the route to check 
	 * @return true if the route is external, false otherwise
	 */
	private boolean isRouteExternal(RouteHeader routeHeader) {
		if (routeHeader != null) {
			javax.sip.address.SipURI routeUri = (javax.sip.address.SipURI) routeHeader.getAddress().getURI();

			String routeTransport = routeUri.getTransportParam();
			if(routeTransport == null) {
				routeTransport = ListeningPoint.UDP;
			}					
			return isExternal(routeUri.getHost(), routeUri.getPort(), routeTransport);						
		}		
		return true;
	}
	
	/**
	 * Check if the via header is external
	 * @param viaHeader the via header to check 
	 * @return true if the via header is external, false otherwise
	 */
	private boolean isViaHeaderExternal(ViaHeader viaHeader) {
		if (viaHeader != null) {			
			return isExternal(viaHeader.getHost(), viaHeader.getPort(), viaHeader.getTransport());
		}
		return true;
	}
	
	/**
	 * Check whether or not the triplet host, port and transport are corresponding to an interface
	 * @param host canbe hostname or ipaddress
	 * @param port port number
	 * @param transport transport used
	 * @return true if the triplet host, port and transport are corresponding to an interface
	 * false otherwise
	 */
	private boolean isExternal(String host, int port, String transport) {
		boolean isExternal = true;
		ListeningPoint listeningPoint = JainSipUtils.findMatchingListeningPoint(
				sipFactoryImpl.getSipProviders(), host, port, transport);
		if(hostNames.contains(host) || listeningPoint != null) {
			isExternal = false;
		}		
		if(logger.isDebugEnabled()) {
			logger.debug("the triplet host/port/transport : " + 
					host + "/" +
					port + "/" +
					transport + " is external : " + isExternal);
		}
		return isExternal;
	}
	
	/**
	 * Method checking whether or not the sip servlet request in parameter is initial
	 * according to algorithm defined in JSR289 Appendix B
	 * @param sipServletRequest the sip servlet request to check
	 * @param dialog the dialog associated with this request
	 * @return true if the request is initial false otherwise
	 */
	private RoutingState checkRoutingState(SipServletRequestImpl sipServletRequest, Dialog dialog) {
		// 2. Ongoing Transaction Detection - Employ methods of Section 17.2.3 in RFC 3261 
		//to see if the request matches an existing transaction. 
		//If it does, stop. The request is not an initial request.		
		if(dialog != null && DialogState.CONFIRMED.equals(dialog.getState())) {
			return RoutingState.SUBSEQUENT;
		}
		// 3. Examine Request Method. If it is CANCEL, BYE, PRACK or ACK, stop. 
		//The request is not an initial request for which application selection occurs.
		if(nonInitialSipRequestMethods.contains(sipServletRequest.getMethod())) {
			return RoutingState.SUBSEQUENT;
		}
		// 4. Existing Dialog Detection - If the request has a tag in the To header field, 
		// the container computes the dialog identifier (as specified in section 12 of RFC 3261) 
		// corresponding to the request and compares it with existing dialogs. 
		// If it matches an existing dialog, stop. The request is not an initial request. 
		// The request is a subsequent request and must be routed to the application path 
		// associated with the existing dialog. 
		// If the request has a tag in the To header field, 
		// but the dialog identifier does not match any existing dialogs, 
		// the container must reject the request with a 481 (Call/Transaction Does Not Exist). 
		// Note: When this occurs, RFC 3261 says either the UAS has crashed or the request was misrouted. 
		// In the latter case, the misrouted request is best handled by rejecting the request. 
		// For the Sip Servlet environment, a UAS crash may mean either an application crashed 
		// or the container itself crashed. In either case, it is impossible to route the request 
		// as a subsequent request and it is inappropriate to route it as an initial request. 
		// Therefore, the only viable approach is to reject the request.
		if(dialog != null && !DialogState.EARLY.equals(dialog.getState())) {
			return RoutingState.SUBSEQUENT;
		}
		// 5. Detection of Merged Requests - If the From tag, Call-ID, and CSeq exactly 
		// match those associated with an ongoing transaction, 
		// the request has arrived more than once across different paths, most likely due to forking. 
		// Such requests represent merged requests and MUST NOT be treated as initial requests. 
		// Refer to section 11.3 for more information on container treatment of merged requests.
		
		// The jain sip stack will send the 482 for us, in the other case the app will see the merged request 
		// and be able to proxy it. Jain sip rocks and rolls !
//		Iterator<SipSessionImpl> sipSessionIterator = sessionManager.getAllSipSessions();
//		while (sipSessionIterator.hasNext()) {
//			SipSessionImpl sipSessionImpl = (SipSessionImpl) sipSessionIterator
//					.next();				
//			Set<Transaction> transactions = sipSessionImpl.getOngoingTransactions();		
//			for (Transaction transaction : transactions) {
//				Request onGoingRequest = transaction.getRequest();
//				Request currentRequest = (Request) sipServletRequest.getMessage();
//				//Get the from headers
//				FromHeader onGoingFromHeader = (FromHeader) onGoingRequest.getHeader(FromHeader.NAME);
//				FromHeader currentFromHeader = (FromHeader) currentRequest.getHeader(FromHeader.NAME);
//				//Get the CallId headers
//				CallIdHeader onGoingCallIdHeader = (CallIdHeader) onGoingRequest.getHeader(CallIdHeader.NAME);
//				CallIdHeader currentCallIdHeader = (CallIdHeader) currentRequest.getHeader(CallIdHeader.NAME);
//				//Get the CSeq headers
//				CSeqHeader onGoingCSeqHeader = (CSeqHeader) onGoingRequest.getHeader(CSeqHeader.NAME);
//				CSeqHeader currentCSeqHeader = (CSeqHeader) currentRequest.getHeader(CSeqHeader.NAME);
//				if(onGoingCSeqHeader.equals(currentCSeqHeader) &&
//						onGoingCallIdHeader.equals(currentCallIdHeader) &&
//						onGoingFromHeader.equals(currentFromHeader)) {
//					return RoutingState.MERGED;
//				}
//			}
//		}
		
		// TODO 6. Detection of Requests Sent to Encoded URIs - 
		// Requests may be sent to a container instance addressed to a URI obtained by calling 
		// the encodeURI() method of a SipApplicationSession managed by this container instance. 
		// When a container receives such a request, stop. This request is not an initial request. 
		// Refer to section 15.9.2 Simple call with no modifications of requests 
		// for more information on how a request sent to an encoded URI is handled by the container.
		
		return RoutingState.INITIAL;		
	}	
	
	/**
	 * {@inheritDoc}
	 */
	public void processResponse(ResponseEvent responseEvent) {
		logger.info("Response " + responseEvent.getResponse().toString());
		Response response = responseEvent.getResponse();
		// See if this transaction has been here before		
		ClientTransaction clientTransaction = responseEvent.getClientTransaction();
		Dialog dialog = responseEvent.getDialog();
			
		ListIterator<ViaHeader> viaHeaders = response.getHeaders(ViaHeader.NAME);				
		ViaHeader viaHeader = (ViaHeader) viaHeaders.next();
		boolean continueRouting = true;		
		if(!isViaHeaderExternal(viaHeader)) {
			continueRouting = routeResponse(response, viaHeader, clientTransaction, dialog);			
		} 
		// if continue routing is to true it means that
		// a B2BUA got it so we don't have anything to do here 
		// or an app that didn't do anything with it
		// or a when handling the request an app had to be called but wasn't deployed
		// we have to strip the topmost via header and forward it statefully		
		if (continueRouting) {			
			Response newResponse = (Response) response.clone();
			newResponse.removeFirst(ViaHeader.NAME);
			ListIterator<ViaHeader> viaHeadersLeft = newResponse.getHeaders(ViaHeader.NAME);
			if(viaHeadersLeft.hasNext()) {
				//forward it statefully
				//TODO should decrease the max forward header to avoid infinite loop
				if(logger.isDebugEnabled()) {
					logger.debug("forwarding the response statefully " + newResponse);
				}
				ServerTransaction serverTransaction = (ServerTransaction)
				((TransactionApplicationData)clientTransaction.getApplicationData()).getTransaction();
				try {
					serverTransaction.sendResponse(newResponse);
				} catch (SipException e) {
					logger.error("cannot forward the response statefully" , e);
				} catch (InvalidArgumentException e) {
					logger.error("cannot forward the response statefully" , e);
				}				
			} else {
				//B2BUA case we don't have to do anything here
				//no more via header B2BUA is the end point
				if(logger.isDebugEnabled()) {
					logger.debug("Not forwarding the response statefully. " +
							"It was either an endpoint or a B2BUA, ie an endpoint too " + newResponse);
				}
			}			
		}
								
	}

	/**
	 * @param responseEvent
	 * @param response
	 * @param address
	 * @return
	 */
	private boolean routeResponse(Response response, ViaHeader viaHeader, ClientTransaction clientTransaction, Dialog dialog) {
		logger.info("viaHeader = " + viaHeader.toString());
		String appNameNotDeployed = viaHeader.getParameter(APP_NOT_DEPLOYED);
		if(appNameNotDeployed != null && appNameNotDeployed.length() > 0) {
			return true;
		}
		String appName = viaHeader.getParameter(RR_PARAM_APPLICATION_NAME); 
		String handlerName = viaHeader.getParameter(RR_PARAM_HANDLER_NAME);
		boolean inverted = false;
		if(dialog != null && dialog.isServer()) {
			inverted = true;
		}
		SipSessionKey sessionKey = SessionManager.getSipSessionKey(appName, response, inverted);
		SipSessionImpl session = sessionManager.getSipSession(sessionKey, false, sipFactoryImpl);									
		// Transate the repsponse to SipServletResponse
		SipServletResponseImpl sipServletResponse = new SipServletResponseImpl(
				response, 
				sipFactoryImpl,
				clientTransaction, 
				session, 
				dialog, 
				null);

		// Update Session state
		session.updateStateOnResponse(sipServletResponse);
		
		try {
			session.setHandler(handlerName);
			// See if this is a response to a proxied request
			ProxyBranchImpl proxyBranch = session.getProxyBranch();
			if(proxyBranch != null) {
				// Handle it at the branch
				proxyBranch.onResponse(sipServletResponse); 
				
				// Notfiy the servlet
				if(proxyBranch.getProxy().getSupervised()) {
					callServlet(sipServletResponse, session);
				}
				return false;
			}
			else {
				callServlet(sipServletResponse, session);
			}
		} catch (ServletException e) {				
			logger.error(e);
			// Sends a 500 Internal server error and stops processing.				
//				JainSipUtils.sendErrorResponse(Response.SERVER_INTERNAL_ERROR, clientTransaction, request, sipProvider);
			return false;
		} catch (IOException e) {				
			logger.error(e);
			// Sends a 500 Internal server error and stops processing.				
//				JainSipUtils.sendErrorResponse(Response.SERVER_INTERNAL_ERROR, clientTransaction, request, sipProvider);
			return false;
		} catch (Throwable e) {
			e.printStackTrace();
			logger.error(e);
			// Sends a 500 Internal server error and stops processing.				
//				JainSipUtils.sendErrorResponse(Response.SERVER_INTERNAL_ERROR, clientTransaction, request, sipProvider);
			return false;
		}		
			
		return true;
	}
	
	public static void callServlet(SipServletRequestImpl request, SipSessionImpl session) throws ServletException, IOException {
		Container container = ((SipApplicationSessionImpl)session.getApplicationSession()).getSipContext().findChild(session.getHandler());
		Wrapper sipServletImpl = (Wrapper) container;
		Servlet servlet = sipServletImpl.allocate();	        
		servlet.service(request, null);		
	}
	
	public static void callServlet(SipServletResponseImpl response, SipSessionImpl session) throws ServletException, IOException {
		logger.info("Dispatching response " + response.toString() + 
				" to following App/servlet => " + session.getKey().getApplicationName()+ 
				"/" + session.getHandler());
		Container container = ((SipApplicationSessionImpl)session.getApplicationSession()).getSipContext().findChild(session.getHandler());
		Wrapper sipServletImpl = (Wrapper) container;		
		Servlet servlet = sipServletImpl.allocate();	        
		servlet.service(null, response);		
	}
	
	/**
	 * {@inheritDoc}
	 */
	public void processTimeout(TimeoutEvent timeoutEvent) {
		// TODO: FIX ME
		if(timeoutEvent.isServerTransaction()) {
			logger.info("timeout => " + timeoutEvent.getServerTransaction().getRequest().toString());
		} else {
			logger.info("timeout => " + timeoutEvent.getClientTransaction().getRequest().toString());
		}
		
		Transaction transaction = null;
		if(timeoutEvent.isServerTransaction()) {
			transaction = timeoutEvent.getServerTransaction();
		} else {
			transaction = timeoutEvent.getClientTransaction();
		}
		TransactionApplicationData tad = (TransactionApplicationData) transaction.getApplicationData();		
		tad.getSipServletMessage().getSipSession().removeOngoingTransaction(transaction);

	}
	/**
	 * {@inheritDoc}
	 */
	public void processTransactionTerminated(TransactionTerminatedEvent transactionTerminatedEvent) {
		// TODO: FIX ME		
		Transaction transaction = null;
		if(transactionTerminatedEvent.isServerTransaction()) {
			transaction = transactionTerminatedEvent.getServerTransaction();
		} else {
			transaction = transactionTerminatedEvent.getClientTransaction();
		}
		logger.info("transaction terminated => " + transaction.getRequest().toString());		
		
		TransactionApplicationData tad = (TransactionApplicationData) transaction.getApplicationData();		
		tad.getSipServletMessage().getSipSession().removeOngoingTransaction(transaction);				
	}

	/**
	 * @return the sipApplicationRouter
	 */
	public SipApplicationRouter getSipApplicationRouter() {
		return sipApplicationRouter;
	}

	/**
	 * @param sipApplicationRouter the sipApplicationRouter to set
	 */
	public void setSipApplicationRouter(SipApplicationRouter sipApplicationRouter) {
		this.sipApplicationRouter = sipApplicationRouter;
	}
	/*
	 * (non-Javadoc)
	 * @see org.mobicents.servlet.sip.core.SipApplicationDispatcher#addSipProvider(javax.sip.SipProvider)
	 */
	public void addSipProvider(SipProvider sipProvider) {
		sipFactoryImpl.addSipProvider(sipProvider);
		if(started) {
			resetOutboundInterfaces();
		}
	}
	/*
	 * (non-Javadoc)
	 * @see org.mobicents.servlet.sip.core.SipApplicationDispatcher#removeSipProvider(javax.sip.SipProvider)
	 */
	public void removeSipProvider(SipProvider sipProvider) {
		sipFactoryImpl.removeSipProvider(sipProvider);
		if(started) {
			resetOutboundInterfaces();
		}
	}
	/*
	 * (non-Javadoc)
	 * @see org.mobicents.servlet.sip.core.SipApplicationDispatcher#getSipProviders()
	 */
	public Set<SipProvider> getSipProviders() {
		return sipFactoryImpl.getSipProviders();
	}
	/*
	 * (non-Javadoc)
	 * @see org.mobicents.servlet.sip.core.SipApplicationDispatcher#getSipFactory()
	 */
	public SipFactory getSipFactory() {
		return sipFactoryImpl;
	}

	/*
	 * (non-Javadoc)
	 * @see org.mobicents.servlet.sip.core.SipApplicationDispatcher#getOutboundInterfaces()
	 */
	public List<SipURI> getOutboundInterfaces() {
		List<SipURI> outboundInterfaces = new ArrayList<SipURI>();
		Set<SipProvider> sipProviders = sipFactoryImpl.getSipProviders();
		for (SipProvider sipProvider : sipProviders) {
			ListeningPoint[] listeningPoints = sipProvider.getListeningPoints();
			for (int i = 0; i < listeningPoints.length; i++) {
				javax.sip.address.SipURI jainSipURI;
				try {
					jainSipURI = SipFactories.addressFactory.createSipURI(
							null, listeningPoints[i].getIPAddress());
					jainSipURI.setPort(listeningPoints[i].getPort());
					jainSipURI.setTransportParam(listeningPoints[i].getTransport());
					SipURI sipURI = new SipURIImpl(jainSipURI);
					outboundInterfaces.add(sipURI);
				} catch (ParseException e) {
					logger.error("cannot add the following listening point "+
							listeningPoints[i].getIPAddress()+":"+
							listeningPoints[i].getPort()+";transport="+
							listeningPoints[i].getTransport()+" to the outbound interfaces", e);
				}				
			}
		}
		
		return Collections.unmodifiableList(outboundInterfaces);
	}
	
	/**
	 * Reset the outbound interfaces on all servlet context of applications deployed
	 */
	private void resetOutboundInterfaces() {
		List<SipURI> outboundInterfaces = getOutboundInterfaces();
		for (SipContext sipContext : applicationDeployed.values()) {
			sipContext.getServletContext().setAttribute(javax.servlet.sip.SipServlet.OUTBOUND_INTERFACES,
					outboundInterfaces);				
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.mobicents.servlet.sip.core.SipApplicationDispatcher#addHostName(java.lang.String)
	 */
	public void addHostName(String hostName) {
		if(logger.isDebugEnabled()) {
			logger.debug(this);
			logger.debug("Adding hostname "+ hostName);
		}
		hostNames.add(hostName);
	}

	/*
	 * (non-Javadoc)
	 * @see org.mobicents.servlet.sip.core.SipApplicationDispatcher#findHostNames()
	 */
	public List<String> findHostNames() {		
		return Collections.unmodifiableList(hostNames);
	}

	/*
	 * (non-Javadoc)
	 * @see org.mobicents.servlet.sip.core.SipApplicationDispatcher#removeHostName(java.lang.String)
	 */
	public void removeHostName(String hostName) {
		if(logger.isDebugEnabled()) {
			logger.debug("Removing hostname "+ hostName);
		}
		hostNames.remove(hostName);
	}
}
