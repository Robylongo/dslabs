package dslabs.primarybackup;

import static dslabs.primarybackup.PingCheckTimer.PING_CHECK_MILLIS;

import dslabs.framework.Address;
import dslabs.framework.Node;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
class ViewServer extends Node {
  static final int STARTUP_VIEWNUM = 0;
  private static final int INITIAL_VIEWNUM = 1;
  private int currentViewNum = STARTUP_VIEWNUM;
  private ArrayList<Address> view;
  private int pingCounter;
  private HashMap<Address, Integer> serverPingCount;
  private ArrayList<Address> deadServers;
  private ArrayList<Address> idleServers;
  private boolean acknowledged;

  /* -----------------------------------------------------------------------------------------------
   *  Construction and Initialization
   * ---------------------------------------------------------------------------------------------*/
  public ViewServer(Address address) {
    super(address);
  }

  @Override
  public void init() {
    view = null;
    deadServers = new ArrayList<>();
    idleServers = new ArrayList<>();
    pingCounter = 1;
    serverPingCount = new HashMap<>();
    // the startup view has no primary, so there is nothing to acknowledge
    acknowledged = true;

    set(new PingCheckTimer(), PING_CHECK_MILLIS);
  }

  /* -----------------------------------------------------------------------------------------------
   *  Message Handlers
   * ---------------------------------------------------------------------------------------------*/
  private void handlePing(Ping m, Address sender) {
    serverPingCount.put(sender, pingCounter);

    // Primary acknowledgment
    if (checkPrimary(sender) && m.viewNum() == currentViewNum) {
      acknowledged = true;
    }

    // had to check
    if (!checkPrimary(sender) && !checkBackup(sender) && !idleServers.contains(sender)) {
      idleServers.add(sender);
    }

    // this ping may have supplied a first primary or a missing backup
    setView(view == null, view == null || view.get(1) == null);
    send(new ViewReply(currentView()), sender);
  }

  private void handleGetView(GetView m, Address sender) {
    send(new ViewReply(currentView()), sender);
  }

  /* -----------------------------------------------------------------------------------------------
   *  Timer Handlers
   * ---------------------------------------------------------------------------------------------*/
  private void onPingCheckTimer(PingCheckTimer t) {
    boolean missingPrimary = false;
    boolean missingBackup = false;
    // Your code here...
    for (Map.Entry<Address, Integer> entry : serverPingCount.entrySet()) {
      Address key = entry.getKey();
      Integer value = entry.getValue();
      if (value == pingCounter) {
        // if it comes back to life, if not put it on idle
        if ((deadServers.contains(key) || view == null)
            && !checkPrimary(key)
            && !checkBackup(key)
            && !idleServers.contains(key)) {
          idleServers.add(key);
        }
        deadServers.remove(key);
      }
      else {
        if (!deadServers.contains(key)) {
          deadServers.add(key);
        }
        // a dead server is no longer a candidate for the backup slot
        idleServers.remove(key);
        // if primary or backup, remove them from that
        if (checkPrimary(key)) {
          missingPrimary = true;
        }
        if (checkBackup(key)) {
          missingBackup = true;
        }
      }
    }

    if (view == null) {
      missingPrimary = true;
      missingBackup = true;
    } else if (view.get(1) == null) {
      missingBackup = true;
    }
    setView(missingPrimary,missingBackup);
    pingCounter++;
    set(t, PING_CHECK_MILLIS);
  }

  /* -----------------------------------------------------------------------------------------------
   *  Utils
   * ---------------------------------------------------------------------------------------------*/
  // Your code here...
  private boolean checkPrimary(Address address) {
    // Checks if address is the primary
    return view != null && address.equals(view.getFirst());
  }

  private boolean checkBackup(Address address) {
    // check if address is the backup
    return view != null && address.equals(view.get(1));
  }

  private View currentView() {
    // The view handed back in every ViewReply
    if (view == null) {
      return new View(STARTUP_VIEWNUM, null, null);
    }
    return new View(currentViewNum, view.getFirst(), view.get(1));
  }


  private Address nextIdle() {
    // Removes and returns the next idle server, or null if there are none
    return idleServers.isEmpty() ? null : idleServers.removeFirst();
  }

  /**
   * Sets a new view filling whichever slots are missing. A new primary is always the previous
   * view's primary or backup, so the service's state is preserved; the only exception is the very
   * first view, whose primary comes from the idle list. If the primary is missing with no backup to
   * promote, the view is left unchanged and the service is stuck. The backup slot is refilled from
   * the idle list, and is left empty if no idle server is available.
   *
   * @param missingPrimary whether the primary slot needs to be filled
   * @param missingBackup whether the backup slot needs to be filled
   */
  private void setView(boolean missingPrimary, boolean missingBackup) {
    // the current view is frozen until its primary acknowledges it
    if (!acknowledged || (!missingPrimary && !missingBackup)) {
      return;
    }

    Address primary;
    if (!missingPrimary) {
      primary = view.getFirst();
    } else if (view == null) {
      // Startup, ny server can be the first primary.
      primary = nextIdle();
      if (primary == null) {
        return;
      }
    } else if (missingBackup) {
      // The primary is gone and there is no backup . No idle server is an
      // eligible successor, so the service stays stuck in this view. Flaw of the system according ot lab
      return;
    } else {
      primary = view.get(1);
    }

    ArrayList<Address> newView = new ArrayList<>(2);
    newView.add(primary);
    newView.add(nextIdle());
    // backup died with no replacement, the view is already current.
    if (newView.equals(view)) {
      return;
    }
    view = newView;
    currentViewNum++;
    acknowledged = false;
  }
}
