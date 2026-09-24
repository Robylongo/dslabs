package dslabs.primarybackup;

import static dslabs.primarybackup.ForwardTimer.FORWARD_RETRY_MILLIS;
import static dslabs.primarybackup.PingTimer.PING_MILLIS;
import static dslabs.primarybackup.StateTransferTimer.STATE_TRANSFER_MILLIS;

import dslabs.atmostonce.AMOApplication;
import dslabs.atmostonce.AMOCommand;
import dslabs.atmostonce.AMOResult;
import dslabs.framework.Address;
import dslabs.framework.Application;
import dslabs.framework.Node;
import java.util.LinkedList;
import java.util.Queue;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
class PBServer extends Node {
  private final Address viewServer;

  // Your code here...
  private AMOApplication<Application> app;
  private boolean isPrimary;
  private boolean isBackup;
  // !(isPrimary || isBackup)
  private boolean isIdle;
  private Address backupServer;
  private Address primaryServer;
  private int viewNum;
  // Primary sends this viewNum to the ViewServer
  // until its backup has our state, so the ViewServer can never promote an uninitialized backup.
  private int pingViewNum;
  // Client
  // requests are rejected until it finishes
  private boolean inStateTransfer;
  // for backup, rhe state transfer for the current view has been applied.
  private boolean stateReceived;
  // The queue for the primary so that commands are executed in the same order
  private final Queue<AMOCommand> pending = new LinkedList<>();
  // Forward for the head of queue is waiting
  private boolean awaitingAck;

  /* -----------------------------------------------------------------------------------------------
   *  Construction and Initialization
   * ---------------------------------------------------------------------------------------------*/
  PBServer(Address address, Address viewServer, Application app) {
    super(address);
    this.viewServer = viewServer;

    // Your code here...
    this.app = new AMOApplication<>(app);
  }

  @Override
  public void init() {
    // Your code here...
    isPrimary = false;
    isBackup = false;
    isIdle = true;
    primaryServer = null;
    backupServer = null;
    send(new Ping(0), viewServer);
    set(new PingTimer(), PING_MILLIS);
  }

  /* -----------------------------------------------------------------------------------------------
   *  Message Handlers
   * ---------------------------------------------------------------------------------------------*/
  private void handleRequest(Request m, Address sender) {
    // Your code here...
    // Only the primary of the current view should serve this, and it has to get the command to the
    // backup before executing it.
    if (inStateTransfer || isBackup || isIdle) {
      send(new Rejected(viewNum, m.command()), sender);
      return;
    }

    if (!pending.contains(m.command())) {
      pending.add(m.command());
    }
    processQueue();
  }

  private void handleViewReply(ViewReply m, Address sender) {
    // Your code here...
    // stale or duplicate
    if (m.view().viewNum() <= viewNum) {
      return;
    }
    viewNum = m.view().viewNum();
    isPrimary = false;
    isBackup = false;
    // work in flight from the old view is dead, and a backup has to be re-sent this view's state
    inStateTransfer = false;
    stateReceived = false;
    awaitingAck = false;
    primaryServer = m.view().primary();
    if (this.address().equals(m.view().primary())) {
      isPrimary = true;
      backupServer = m.view().backup();
      if (backupServer != null) {
        inStateTransfer = true;
        send(new StateTransfer(viewNum, app), backupServer);
        set(new StateTransferTimer(viewNum), STATE_TRANSFER_MILLIS);
      }
    } else if (this.address().equals(m.view().backup())) {

      isBackup = true;
    }
    isIdle = !(isPrimary || isBackup);
    if (isIdle || isBackup) {
      backupServer = null;
      pending.clear();
    }
    // Hold the ack back while a transfer is outstanding, so the ViewServer can't promote a backup
    // that doesn't have our state yet.
    if (!inStateTransfer) {
      pingViewNum = viewNum;
    }
    processQueue();
  }

  // For the backup, a command the primary wants applied before it executes and answers the client.
  private void handleForward(Forward m, Address sender) {
    if (!isBackup || isIdle) {
      // idk what should happen
      return;
    }

    if (m.viewNum() != viewNum || !stateReceived) {
      return;
    }
    app.execute(m.command());
    send(new ForwardReply(viewNum, m.command(), m.clientAddress()), sender);
  }

  // For the primary, the backup has applied a forwarded command.
  private void handleForwardReply(ForwardReply m, Address sender) {
    if (!isPrimary || m.viewNum() != viewNum || !sender.equals(backupServer)) {
      // idk what to do
      return;
    }

    if (!awaitingAck || pending.isEmpty() || !pending.peek().equals(m.command())) {
      return;
    }
    AMOCommand command = pending.poll();
    AMOResult result = app.execute(command);
    awaitingAck = false;
    send(new Reply(result), m.clientAddress());
    processQueue();
  }

  // For the backup, the primary's application state for the view we were just added to.
  private void handleStateTransfer(StateTransfer m, Address sender) {
    if (m.viewNum() != viewNum) {
      return;
    }
    // Apply once per view; a retransmission must not clobber state we've taken on since.
    if (!stateReceived) {
      app = m.application();
      stateReceived = true;
    }
    // Reply either way, so a lost reply still gets answered.
    send(new StateTransferReply(viewNum), sender);
  }

  // For the primary, the backup has our state, so the view can be acknowledged.
  private void handleStateTransferReply(StateTransferReply m, Address sender) {
    // A reply from an older view must not acknowledge this one
    if (!isPrimary || !inStateTransfer || m.viewNum() != viewNum || !sender.equals(backupServer)) {
      return;
    }
    inStateTransfer = false;
    pingViewNum = viewNum;
    processQueue();
  }

  /* -----------------------------------------------------------------------------------------------
   *  Timer Handlers
   * ---------------------------------------------------------------------------------------------*/
  private void onPingTimer(PingTimer t) {
    send(new Ping(pingViewNum), viewServer);
    set(new PingTimer(), PING_MILLIS);
  }

  // For the primary, resend a forwarded command that hasn't been acknowledged.
  private void onForwardTimer(ForwardTimer t) {
    if (inStateTransfer || isBackup || isIdle) {
      return;
    }

    if (!awaitingAck
        || t.viewNum() != viewNum
        || pending.isEmpty()
        || !pending.peek().equals(t.command())) {
      return;
    }
    // Retry only while this exact command is still the head of this view's queue
    send(new Forward(viewNum, t.command(), t.clientAddress()), backupServer);
    set(new ForwardTimer(viewNum, t.command(), t.clientAddress()), FORWARD_RETRY_MILLIS);
  }

  // For the primary, resend the state transfer while it's still outstanding for this view.
  private void onStateTransferTimer(StateTransferTimer t) {
    // The viewNum check keeps a chain from an earlier view from outliving it
    if (!inStateTransfer || t.viewNum() != viewNum) {
      return;
    }
    send(new StateTransfer(viewNum, app), backupServer);
    set(new StateTransferTimer(viewNum), STATE_TRANSFER_MILLIS);
  }

  /* -----------------------------------------------------------------------------------------------
   *  Utils
   * ---------------------------------------------------------------------------------------------*/
  // Your code here...
  /**
   * Forwards the head of the queue to the backup, or executes it outright when there is no backup.
   * Only the head is ever in flight, so the backup applies commands in the same order we do.
   */
  private void processQueue() {
    while (isPrimary && !inStateTransfer && !awaitingAck && !pending.isEmpty()) {
      AMOCommand command = pending.peek();
      if (backupServer == null) {
        // Nothing to replicate to, so execute and answer right away, then take the next one.
        AMOResult result = app.execute(command);
        send(new Reply(result), command.clientAddress());
        pending.poll();
        continue;
      }
      send(new Forward(viewNum, command, command.clientAddress()), backupServer);
      set(new ForwardTimer(viewNum, command, command.clientAddress()), FORWARD_RETRY_MILLIS);
      awaitingAck = true;
    }
  }
}
