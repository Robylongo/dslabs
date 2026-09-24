package dslabs.primarybackup;

import static dslabs.primarybackup.ClientTimer.CLIENT_RETRY_MILLIS;

import dslabs.atmostonce.AMOCommand;
import dslabs.atmostonce.AMOResult;
import dslabs.framework.Address;
import dslabs.framework.Client;
import dslabs.framework.Command;
import dslabs.framework.Node;
import dslabs.framework.Result;
import dslabs.kvstore.KVStore.KVStoreCommand;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
class PBClient extends Node implements Client {
  private final Address viewServer;
  private Address primaryServer;

  // Your code here...
  private int sequenceNumber = 0;
  private int viewNumber = 0;
  private AMOCommand request;
  private AMOResult response;

  /* -----------------------------------------------------------------------------------------------
   *  Construction and Initialization
   * ---------------------------------------------------------------------------------------------*/
  public PBClient(Address address, Address viewServer) {
    super(address);
    this.viewServer = viewServer;
  }

  @Override
  public synchronized void init() {
    // Your code here...

    send(new GetView(), viewServer);
  }

  /* -----------------------------------------------------------------------------------------------
   *  Client Methods
   * ---------------------------------------------------------------------------------------------*/
  @Override
  public synchronized void sendCommand(Command command) {
    if (!(command instanceof KVStoreCommand)) {
      throw new IllegalArgumentException();
    }

    sequenceNumber++;
    this.request = new AMOCommand(command, address(), sequenceNumber);
    this.response = null;

    sendRequest();
    set(new ClientTimer(request), CLIENT_RETRY_MILLIS);
  }

  @Override
  public synchronized boolean hasResult() {
    return response != null;
  }

  @Override
  public synchronized Result getResult() throws InterruptedException {
    while (response == null) {
      wait();
    }

    return response.result();
  }

  /* -----------------------------------------------------------------------------------------------
   *  Message Handlers
   * ---------------------------------------------------------------------------------------------*/
  private synchronized void handleReply(Reply m, Address sender) {
    AMOResult r = m.result();
    if (r.sequenceNum() == sequenceNumber && response == null) {
      response = r;
      notify();
    }
  }

  private synchronized void handleViewReply(ViewReply m, Address sender) {
    // Your code here...
    if (m.view().viewNum() <= viewNumber) {
      return;
    }
    primaryServer = m.view().primary();
    viewNumber = m.view().viewNum();

    // primary changes mid request
    if (request != null && response == null) {
      sendRequest();
    }
  }

  // When it gets a no no reply, send view request
  private synchronized void handleRejected(Rejected m, Address sender) {
    if (m.viewNum() <= viewNumber) {
      return;
    }
    primaryServer = null;
    sendRequest();
    //    send(new GetView(), viewServer);
  }

  // Your code here...

  /* -----------------------------------------------------------------------------------------------
   *  Timer Handlers
   * ---------------------------------------------------------------------------------------------*/
  private synchronized void onClientTimer(ClientTimer t) {
    if (request != null && t.command().sequenceNum() == request.sequenceNum() && response == null) {
      send(new GetView(), viewServer);
      sendRequest();
      set(t, CLIENT_RETRY_MILLIS);
    }
  }

  /* -----------------------------------------------------------------------------------------------
   *  Utils
   * ---------------------------------------------------------------------------------------------*/
  /**
   * Sends the outstanding request to the primary we know about. If we don't know of one, asks the
   * ViewServer for the current view instead.
   */
  private void sendRequest() {
    if (primaryServer == null) {
      send(new GetView(), viewServer);
      return;
    }

    send(new Request(request), primaryServer);
  }
}
