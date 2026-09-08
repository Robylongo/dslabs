package dslabs.atmostonce;

import dslabs.framework.Address;
import dslabs.framework.Application;
import dslabs.framework.Command;
import dslabs.framework.Result;
import java.util.HashMap;
import java.util.Map;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

@EqualsAndHashCode
@ToString
@RequiredArgsConstructor
public final class AMOApplication<T extends Application> implements Application {
  @Getter @NonNull private final T application;

  private final Map<Address, AMOResult> clientAMOMap = new HashMap<>();

  @Override
  public AMOResult execute(Command command) {
    if (!(command instanceof AMOCommand amoCommand)) {
      throw new IllegalArgumentException();
    }

    AMOResult prev = clientAMOMap.get(amoCommand.clientAddress());
    if (prev != null && amoCommand.sequenceNum() <= prev.sequenceNum()) {
      return prev;
    }

    Result r = application.execute(amoCommand.command());
    AMOResult amoResult = new AMOResult(r, amoCommand.sequenceNum());
    clientAMOMap.put(amoCommand.clientAddress(), amoResult);
    return amoResult;
  }

  public Result executeReadOnly(Command command) {
    if (!command.readOnly()) {
      throw new IllegalArgumentException();
    }

    if (command instanceof AMOCommand) {
      return execute(command);
    }

    return application.execute(command);
  }

  public boolean alreadyExecuted(AMOCommand amoCommand) {
    AMOResult prev = clientAMOMap.get(amoCommand.clientAddress());
    return prev != null && amoCommand.sequenceNum() <= prev.sequenceNum();
  }
}
