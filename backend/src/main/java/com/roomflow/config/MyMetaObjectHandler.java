package com.roomflow.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

/** Auto-fills createdAt/updatedAt/joinedAt with the current time on the injected business clock. */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

  private final Clock clock;

  public MyMetaObjectHandler(Clock clock) {
    this.clock = clock;
  }

  @Override
  public void insertFill(MetaObject metaObject) {
    // DATETIME stores whole seconds; truncate so serialized values match persisted ones.
    LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
    this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
    this.strictInsertFill(metaObject, "joinedAt", LocalDateTime.class, now);
  }

  @Override
  public void updateFill(MetaObject metaObject) {
    this.strictUpdateFill(
        metaObject,
        "updatedAt",
        LocalDateTime.class,
        LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
  }
}
