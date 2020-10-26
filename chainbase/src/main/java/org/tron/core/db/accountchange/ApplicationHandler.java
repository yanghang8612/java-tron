package org.tron.core.db.accountchange;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import org.tron.common.application.TronApplicationContext;

@Component
public class ApplicationHandler implements ApplicationContextAware {

  public static TronApplicationContext applicationContext;

  @Override
  public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
    this.applicationContext = (TronApplicationContext) applicationContext;
  }

  public static void closeSys() {
    applicationContext.destroy();
    applicationContext.close();
    System.exit(0);
  }

}
