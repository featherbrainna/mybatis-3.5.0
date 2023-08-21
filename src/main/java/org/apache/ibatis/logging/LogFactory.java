/**
 *    Copyright 2009-2018 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.logging;

import java.lang.reflect.Constructor;

/**
 * 日志工厂类（简单工厂模式：没有工厂抽象，只有产品抽象）
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public final class LogFactory {

  /**
   * Marker to be used by logging implementations that support markers
   */
  public static final String MARKER = "MYBATIS";

  /**
   * 记录当前使用的第三方日志组件所对应的适配器的构造方法
   */
  private static Constructor<? extends Log> logConstructor;

  static {
    //逐个尝试，判断使用哪个Log的实现类，即初始化 logConstructor
    //按照 Slf4j、CommonsLogging、Log4J2Logging、Log4JLogging、JdkLogging、NoLogging 的顺序，逐个尝试
    tryImplementation(LogFactory::useSlf4jLogging);
    tryImplementation(LogFactory::useCommonsLogging);
    tryImplementation(LogFactory::useLog4J2Logging);
    tryImplementation(LogFactory::useLog4JLogging);
    tryImplementation(LogFactory::useJdkLogging);
    tryImplementation(LogFactory::useNoLogging);
  }

  /**
   * 私有化构造器，静态工具类
   */
  private LogFactory() {
    // disable construction
  }

  //###################################### getLog方法获取日志对象 ###################################################

  public static Log getLog(Class<?> aClass) {
    return getLog(aClass.getName());
  }

  public static Log getLog(String logger) {
    try {
      return logConstructor.newInstance(logger);
    } catch (Throwable t) {
      throw new LogException("Error creating logger for logger " + logger + ".  Cause: " + t, t);
    }
  }

  //##################################### use方法 ######################################################

  /**
   * 设置自定义的 Log 实现类
   * @param clazz 自定义 Log 实现类
   */
  public static synchronized void useCustomLogging(Class<? extends Log> clazz) {
    setImplementation(clazz);
  }

  public static synchronized void useSlf4jLogging() {
    setImplementation(org.apache.ibatis.logging.slf4j.Slf4jImpl.class);
  }

  public static synchronized void useCommonsLogging() {
    setImplementation(org.apache.ibatis.logging.commons.JakartaCommonsLoggingImpl.class);
  }

  public static synchronized void useLog4JLogging() {
    setImplementation(org.apache.ibatis.logging.log4j.Log4jImpl.class);
  }

  public static synchronized void useLog4J2Logging() {
    setImplementation(org.apache.ibatis.logging.log4j2.Log4j2Impl.class);
  }

  public static synchronized void useJdkLogging() {
    setImplementation(org.apache.ibatis.logging.jdk14.Jdk14LoggingImpl.class);
  }

  public static synchronized void useStdOutLogging() {
    setImplementation(org.apache.ibatis.logging.stdout.StdOutImpl.class);
  }

  public static synchronized void useNoLogging() {
    setImplementation(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
  }

  /**
   * 尝试日志具体实现
   * @param runnable
   */
  private static void tryImplementation(Runnable runnable) {
    //1.若logConstructor为空，即还未找到合适的具体的日志实现
    if (logConstructor == null) {
      try {
        //2.委托底层执行传入的方法引用，即各个LogFactory.useXXX方法
        runnable.run();
      } catch (Throwable t) {
        // ignore
        //忽略无法使用的日志组件异常
      }
    }
  }

  /**
   * 设置 logConstructor 字段
   * @param implClass
   */
  private static void setImplementation(Class<? extends Log> implClass) {
    try {
      //1.获取指定的适配器的构造方法，带String参数的构造方法
      Constructor<? extends Log> candidate = implClass.getConstructor(String.class);
      //2.创建适配器对象（继承接口Log被适配的接口）
      Log log = candidate.newInstance(LogFactory.class.getName());
      if (log.isDebugEnabled()) {
        log.debug("Logging initialized using '" + implClass + "' adapter.");
      }
      //3.创建成功，意味着可以使用，初始化 logConstructor 字段
      logConstructor = candidate;
    } catch (Throwable t) {
      //4.无法使用此日志组件，抛出异常，后序被tryImplementation(Runnable runnable)方法捕获后忽略
      throw new LogException("Error setting Log implementation.  Cause: " + t, t);
    }
  }

}
