package org.tron.core.db;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class NewBlockIndexStore extends BlockIndexStore {

  @Autowired
  public NewBlockIndexStore(@Value("new-block-index") String dbName) {
    super(dbName);
  }
}