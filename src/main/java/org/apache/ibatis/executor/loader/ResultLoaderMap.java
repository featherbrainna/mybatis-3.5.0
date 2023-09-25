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
package org.apache.ibatis.executor.loader;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.BaseExecutor;
import org.apache.ibatis.executor.BatchResult;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.sql.SQLException;
import java.util.*;

/**
 * ResultLoader结果加载器的的缓存
 * @author Clinton Begin
 * @author Franta Mejta
 */
public class ResultLoaderMap {

  /**
   * ResultLoader 的映射。该映射最终创建代理对象时，会作为参数传入代理
   * key：大写的属性名
   * value：LoadPair对象，组合了ResultLoader对象
   */
  private final Map<String, LoadPair> loaderMap = new HashMap<>();

  /**
   * 添加到 loaderMap 中
   * @param property 属性名
   * @param metaResultObject 结果对象的MetaObject
   * @param resultLoader ResultLoader对象
   */
  public void addLoader(String property, MetaObject metaResultObject, ResultLoader resultLoader) {
    //1.大写属性名
    String upperFirst = getUppercaseFirstProperty(property);
    //2.如果已存在，则抛出异常
    if (!upperFirst.equalsIgnoreCase(property) && loaderMap.containsKey(upperFirst)) {
      throw new ExecutorException("Nested lazy loaded result property '" + property +
              "' for query id '" + resultLoader.mappedStatement.getId() +
              " already exists in the result map. The leftmost property of all lazy loaded properties must be unique within a result map.");
    }
    //3.创建 LoadPair 对象添加到 loaderMap 中
    loaderMap.put(upperFirst, new LoadPair(property, metaResultObject, resultLoader));
  }

  /**
   * 获取 loaderMap 中的元素映射
   * @return 新的映射对象，里面元素为loaderMap中的元素
   */
  public final Map<String, LoadPair> getProperties() {
    return new HashMap<>(this.loaderMap);
  }

  /**
   * 获取 loaderMap 的属性名集合
   * @return
   */
  public Set<String> getPropertyNames() {
    return loaderMap.keySet();
  }

  /**
   * 获取 loaderMap 的大小
   * @return
   */
  public int size() {
    return loaderMap.size();
  }

  /**
   * 从 loaderMap 判断是否有此属性的加载器
   * @param property 属性名
   * @return 是否有加载器
   */
  public boolean hasLoader(String property) {
    return loaderMap.containsKey(property.toUpperCase(Locale.ENGLISH));
  }

  /**
   * 执行loaderMap指定属性的加载
   * @param property 属性名
   * @return 是否加载成功
   * @throws SQLException
   */
  public boolean load(String property) throws SQLException {
    //1.获得 LoadPair 对象，并从loaderMap移除
    LoadPair pair = loaderMap.remove(property.toUpperCase(Locale.ENGLISH));
    //2.如果pair不为空，调用LoadPair.load()执行加载
    if (pair != null) {
      pair.load();
      //加载成功
      return true;
    }
    //加载失败
    return false;
  }

  public void remove(String property) {
    loaderMap.remove(property.toUpperCase(Locale.ENGLISH));
  }

  /**
   * 执行loaderMap所有属性的加载
   * @throws SQLException
   */
  public void loadAll() throws SQLException {
    //1.从loaderMap获取所有key
    final Set<String> methodNameSet = loaderMap.keySet();
    //2.将属性名集合转换为数组
    String[] methodNames = methodNameSet.toArray(new String[methodNameSet.size()]);
    //3.遍历属性名执行加载属性
    for (String methodName : methodNames) {
      load(methodName);
    }
  }

  /**
   * 使用 . 分隔属性，并获得首个字符串，并大写
   * @param property 属性名
   * @return 字符串+大写
   */
  private static String getUppercaseFirstProperty(String property) {
    String[] parts = property.split("\\.");
    return parts[0].toUpperCase(Locale.ENGLISH);
  }

  /**
   * 内部静态类，加载器。
   * Property which was not loaded yet.
   */
  public static class LoadPair implements Serializable {

    private static final long serialVersionUID = 20130412;
    /**
     * Name of factory method which returns database connection.
     */
    private static final String FACTORY_METHOD = "getConfiguration";
    /**
     * Object to check whether we went through serialization..
     */
    private final transient Object serializationCheck = new Object();
    /**
     * 外层结果对象对应的MetaObject
     * Meta object which sets loaded properties.
     */
    private transient MetaObject metaResultObject;
    /**
     * 结果加载器。负责加载延迟加载属性的ResultLoader对象
     * Result loader which loads unread properties.
     */
    private transient ResultLoader resultLoader;
    /**
     * 日志对象
     * Wow, logger.
     */
    private transient Log log;
    /**
     * 配置对象的工厂类
     * Factory class through which we get database connection.
     */
    private Class<?> configurationFactory;
    /**
     * 外层结果对象的属性名
     * Name of the unread property.
     */
    private String property;
    /**
     * 延迟加载对应的mappedStatement的id
     * ID of SQL statement which loads the property.
     */
    private String mappedStatement;
    /**
     * 延迟加载执行的sql的参数
     * Parameter of the sql statement.
     */
    private Serializable mappedParameter;

    /**
     * 构造器
     * @param property 属性名
     * @param metaResultObject 外层结果对象的MetaObject
     * @param resultLoader 结果加载器
     */
    private LoadPair(final String property, MetaObject metaResultObject, ResultLoader resultLoader) {
      //1.初始化属性property、metaResultObject、resultLoader
      this.property = property;
      this.metaResultObject = metaResultObject;
      this.resultLoader = resultLoader;

      //2.如果结果对象的MetaObject不为空 且 结果对象可序列化
      /* Save required information only if original object can be serialized. */
      if (metaResultObject != null && metaResultObject.getOriginalObject() instanceof Serializable) {
        //2.1从结果加载器获取参数对象
        final Object mappedStatementParameter = resultLoader.parameterObject;

        //2.2如果参数对象可序列化
        /* @todo May the parameter be null? */
        if (mappedStatementParameter instanceof Serializable) {
          //2.2.1从结果加载器获取mappedStatement的id，初始化mappedStatement
          this.mappedStatement = resultLoader.mappedStatement.getId();
          //2.2.2初始化mappedParameter
          this.mappedParameter = (Serializable) mappedStatementParameter;

          //2.2.3初始化configurationFactory
          this.configurationFactory = resultLoader.configuration.getConfigurationFactory();
        } else {
          //2.2如果参数对象不可序列化
          //2.2.1获取日志对象
          Log log = this.getLogger();
          //2.2.2日志记录
          if (log.isDebugEnabled()) {
            log.debug("Property [" + this.property + "] of ["
                    + metaResultObject.getOriginalObject().getClass() + "] cannot be loaded "
                    + "after deserialization. Make sure it's loaded before serializing "
                    + "forenamed object.");
          }
        }
      }
    }

    /**
     * 底层执行延迟加载，由 {@link #load(String)} 调用
     * @throws SQLException
     */
    public void load() throws SQLException {
      /* These field should not be null unless the loadpair was serialized.
       * Yet in that case this method should not be called. */
      //1.如果 metaResultObject 属性为空，则抛出异常
      if (this.metaResultObject == null) {
        throw new IllegalArgumentException("metaResultObject is null");
      }
      //2.如果 resultLoader 属性为空，则抛出异常
      if (this.resultLoader == null) {
        throw new IllegalArgumentException("resultLoader is null");
      }

      //3.执行延迟加载
      this.load(null);
    }

    /**
     * 底层执行延迟加载，由 {@link #load()} 调用
     * @param userObject 指定的外层结果对象
     * @throws SQLException
     */
    public void load(final Object userObject) throws SQLException {
      //1.如果 metaResultObject 为空或 resultLoader为空
      if (this.metaResultObject == null || this.resultLoader == null) {
        //1.1如果 mappedParameter 为空，则抛出异常
        if (this.mappedParameter == null) {
          throw new ExecutorException("Property [" + this.property + "] cannot be loaded because "
                  + "required parameter of mapped statement ["
                  + this.mappedStatement + "] is not serializable.");
        }

        //1.2获取配置对象
        final Configuration config = this.getConfiguration();
        //1.3获取 MappedStatement 对象
        final MappedStatement ms = config.getMappedStatement(this.mappedStatement);
        //1.4如果 MappedStatement 对象为空，则抛出异常
        if (ms == null) {
          throw new ExecutorException("Cannot lazy load property [" + this.property
                  + "] of deserialized object [" + userObject.getClass()
                  + "] because configuration does not contain statement ["
                  + this.mappedStatement + "]");
        }

        //1.5获取指定的 userObject外层结果对象 的 MetaResult
        this.metaResultObject = config.newMetaObject(userObject);
        //1.6创建resultLoader
        this.resultLoader = new ResultLoader(config, new ClosedExecutor(), ms, this.mappedParameter,
                metaResultObject.getSetterType(this.property), null, null);
      }

      /* We are using a new executor because we may be (and likely are) on a new thread
       * and executors aren't thread safe. (Is this sufficient?)
       *
       * A better approach would be making executors thread safe. */
      //2.如果 serializationCheck 属性为空
      if (this.serializationCheck == null) {
        final ResultLoader old = this.resultLoader;
        //设置 resultLoader
        this.resultLoader = new ResultLoader(old.configuration, new ClosedExecutor(), old.mappedStatement,
                old.parameterObject, old.targetType, old.cacheKey, old.boundSql);
      }

      //3.通过底层resultLoader.loadResult()实现加载。再将延迟加载的置设置到外层结果对象
      this.metaResultObject.setValue(property, this.resultLoader.loadResult());
    }

    private Configuration getConfiguration() {
      if (this.configurationFactory == null) {
        throw new ExecutorException("Cannot get Configuration as configuration factory was not set.");
      }

      Object configurationObject = null;
      try {
        final Method factoryMethod = this.configurationFactory.getDeclaredMethod(FACTORY_METHOD);
        if (!Modifier.isStatic(factoryMethod.getModifiers())) {
          throw new ExecutorException("Cannot get Configuration as factory method ["
                  + this.configurationFactory + "]#["
                  + FACTORY_METHOD + "] is not static.");
        }

        if (!factoryMethod.isAccessible()) {
          configurationObject = AccessController.doPrivileged(new PrivilegedExceptionAction<Object>() {
            @Override
            public Object run() throws Exception {
              try {
                factoryMethod.setAccessible(true);
                return factoryMethod.invoke(null);
              } finally {
                factoryMethod.setAccessible(false);
              }
            }
          });
        } else {
          configurationObject = factoryMethod.invoke(null);
        }
      } catch (final ExecutorException ex) {
        throw ex;
      } catch (final NoSuchMethodException ex) {
        throw new ExecutorException("Cannot get Configuration as factory class ["
                + this.configurationFactory + "] is missing factory method of name ["
                + FACTORY_METHOD + "].", ex);
      } catch (final PrivilegedActionException ex) {
        throw new ExecutorException("Cannot get Configuration as factory method ["
                + this.configurationFactory + "]#["
                + FACTORY_METHOD + "] threw an exception.", ex.getCause());
      } catch (final Exception ex) {
        throw new ExecutorException("Cannot get Configuration as factory method ["
                + this.configurationFactory + "]#["
                + FACTORY_METHOD + "] threw an exception.", ex);
      }

      if (!(configurationObject instanceof Configuration)) {
        throw new ExecutorException("Cannot get Configuration as factory method ["
                + this.configurationFactory + "]#["
                + FACTORY_METHOD + "] didn't return [" + Configuration.class + "] but ["
                + (configurationObject == null ? "null" : configurationObject.getClass()) + "].");
      }

      return Configuration.class.cast(configurationObject);
    }

    private Log getLogger() {
      if (this.log == null) {
        this.log = LogFactory.getLog(this.getClass());
      }
      return this.log;
    }
  }

  /**
   * 继承 BaseExecutor 抽象类，已经关闭的 Executor 实现类
   * 一个“空”的 Executor 对象
   */
  private static final class ClosedExecutor extends BaseExecutor {

    public ClosedExecutor() {
      super(null, null);
    }

    @Override
    public boolean isClosed() {
      return true;
    }

    @Override
    protected int doUpdate(MappedStatement ms, Object parameter) throws SQLException {
      throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    protected List<BatchResult> doFlushStatements(boolean isRollback) throws SQLException {
      throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    protected <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
      throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    protected <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql) throws SQLException {
      throw new UnsupportedOperationException("Not supported.");
    }
  }
}
