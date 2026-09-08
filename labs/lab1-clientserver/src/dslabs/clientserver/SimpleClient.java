package dslabs.clientserver;

import static dslabs.clientserver.ClientTimer.CLIENT_RETRY_MILLIS;

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

/**
 * Simple client that sends requests to a single server and returns responses.
 *
 * <p>See the documentation of {@link Client} and {@link Node} for important implementation notes.
 */
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
class SimpleClient extends Node implements Client {
  private final Address serverAddress;

  private int sequenceNumber = 0;
  private AMOCommand request;
  private AMOResult response;

  /* -----------------------------------------------------------------------------------------------
   *  Construction and Initialization
   * ---------------------------------------------------------------------------------------------*/
  public SimpleClient(Address address, Address serverAddress) {
    super(address);
    this.serverAddress = serverAddress;
  }

  @Override
  public synchronized void init() {
    // No initialization necessary
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

    send(new Request(request), serverAddress);
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

  /* -----------------------------------------------------------------------------------------------
   *  Timer Handlers
   * ---------------------------------------------------------------------------------------------*/
  private synchronized void onClientTimer(ClientTimer t) {
    if (request != null && t.command().sequenceNum() == request.sequenceNum() && response == null) {
      send(new Request(request), serverAddress);
      set(t, CLIENT_RETRY_MILLIS);
    }
  }
}
