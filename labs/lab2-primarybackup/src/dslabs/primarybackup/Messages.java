package dslabs.primarybackup;

import dslabs.atmostonce.AMOApplication;
import dslabs.atmostonce.AMOCommand;
import dslabs.atmostonce.AMOResult;
import dslabs.framework.Address;
import dslabs.framework.Application;
import dslabs.framework.Message;
import lombok.Data;

/* -----------------------------------------------------------------------------------------------
 *  ViewServer Messages
 * ---------------------------------------------------------------------------------------------*/
@Data
class Ping implements Message {
  private final int viewNum;
}

@Data
class GetView implements Message {}

@Data
class ViewReply implements Message {
  private final View view;
}

/* -----------------------------------------------------------------------------------------------
 *  Primary-Backup Messages
 * ---------------------------------------------------------------------------------------------*/
@Data
class Request implements Message {
  private final AMOCommand command;
}

@Data
class Reply implements Message {
  private final AMOResult result;
}

// Your code here...
// rejected message
@Data
class Rejected implements Message {
  private final int viewNum;
  private final AMOCommand command;
}

// forwarded message (request) + reply(?) + timer
@Data
class Forward implements Message {
  private final int viewNum;
  private final AMOCommand command;
  private final Address clientAddress;
}

@Data
class ForwardReply implements Message {
  private final int viewNum;
  private final AMOCommand command;
  private final Address clientAddress;
}

// application transfer message + reply(?) + timer
@Data
class StateTransfer implements Message {
  private final int viewNum;
  private final AMOApplication<Application> application;
}

@Data
class StateTransferReply implements Message {
  private final int viewNum;
}
