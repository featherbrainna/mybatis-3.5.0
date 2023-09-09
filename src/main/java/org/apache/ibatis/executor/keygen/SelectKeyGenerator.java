/**
 *    Copyright 2009-2019 the original author or authors.
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
package org.apache.ibatis.executor.keygen;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.RowBounds;

import java.sql.Statement;
import java.util.List;

/**
 * 实现 KeyGenerator 接口，基于从数据库查询生成主键的 KeyGenerator 实现类，适用于 Oracle、PostgreSQL
 * @author Clinton Begin
 * @author Jeff Butler
 */
public class SelectKeyGenerator implements KeyGenerator {

  /**
   * selectKey节点id后缀
   */
  public static final String SELECT_KEY_SUFFIX = "!selectKey";
  /**
   * 标识selectKey节点中定义的SQL语句是在 insert 语句之前执行还是之后执行
   */
  private final boolean executeBefore;
  /**
   * selectKey节点中定义的SQL语句所对应的MappedStatement对象
   */
  private final MappedStatement keyStatement;

  /**
   * 构造器
   * @param keyStatement selectKey节点中定义的SQL语句所对应的MappedStatement对象
   * @param executeBefore 是否在执行写操作前处理主键
   */
  public SelectKeyGenerator(MappedStatement keyStatement, boolean executeBefore) {
    this.executeBefore = executeBefore;
    this.keyStatement = keyStatement;
  }

  @Override
  public void processBefore(Executor executor, MappedStatement ms, Statement stmt, Object parameter) {
    if (executeBefore) {
      processGeneratedKeys(executor, ms, parameter);
    }
  }

  @Override
  public void processAfter(Executor executor, MappedStatement ms, Statement stmt, Object parameter) {
    if (!executeBefore) {
      //Statement没有生成主键，即数据库没有生成主键，故未传入 stmt 参数
      processGeneratedKeys(executor, ms, parameter);
    }
  }

  /**
   * 处理生成并设置主键。被 processBefore 和 processAfter 方法调用
   * @param executor 执行器对象
   * @param ms MappedStatement对象
   * @param parameter 参数对象（参数对象不可能为集合类型！！！）
   */
  private void processGeneratedKeys(Executor executor, MappedStatement ms, Object parameter) {
    try {
      //1.如果 用户参数不为空 且 selectkey节点的sql语句对应的MappedStatement不为空 且 selectkey节点的keyProperty属性不为空
      if (parameter != null && keyStatement != null && keyStatement.getKeyProperties() != null) {
        //2.获取 selectkey节点的 keyProperty属性
        String[] keyProperties = keyStatement.getKeyProperties();
        //获取配置对象
        final Configuration configuration = ms.getConfiguration();
        //3.获取 参数对象 对应的 MetaObject 对象，用于设置主键到参数对象
        final MetaObject metaParam = configuration.newMetaObject(parameter);
        //4.如果 主键属性名集合 不为空
        if (keyProperties != null) {
          // Do not close keyExecutor.
          // The transaction will be closed by parent executor.
          //4.1创建新的 keyExecutor执行器，用于生成主键
          Executor keyExecutor = configuration.newExecutor(executor.getTransaction(), ExecutorType.SIMPLE);
          //4.2执行 selectKey 的sql语句，查找生成主键
          List<Object> values = keyExecutor.query(keyStatement, parameter, RowBounds.DEFAULT, Executor.NO_RESULT_HANDLER);
          //4.3如果查不到结果，抛出异常
          if (values.size() == 0) {
            throw new ExecutorException("SelectKey returned no data.");
          } else if (values.size() > 1) {
            //4.3如果查询的结果过多，抛出异常
            throw new ExecutorException("SelectKey returned more than one value.");
          } else {
            //4.3其它情况，创建 MetaObject 对象，访问查询主键的结果
            //4.3.1获取 主键结果 对应的 MetaObject 对象，用于访问查询主键
            MetaObject metaResult = configuration.newMetaObject(values.get(0));
            //4.3.2如果 主键属性名数组 大小为1（单个主键）
            if (keyProperties.length == 1) {
              //设置 主键属性 到 metaParam 中
              if (metaResult.hasGetter(keyProperties[0])) {
                setValue(metaParam, keyProperties[0], metaResult.getValue(keyProperties[0]));
              } else {
                // no getter for the property - maybe just a single value object
                // so try that
                setValue(metaParam, keyProperties[0], values.get(0));
              }
            } else {
              //4.3.2如果 主键属性名数组 大小为其它
              handleMultipleProperties(keyProperties, metaParam, metaResult);
            }
          }
        }
      }
    } catch (ExecutorException e) {
      throw e;
    } catch (Exception e) {
      throw new ExecutorException("Error selecting key or setting result to parameter object. Cause: " + e, e);
    }
  }

  /**
   * 将多个主键属性设置到参数中
   * 底层调用 {@link #setValue(MetaObject, String, Object)} 方法
   * @param keyProperties 主键属性名数组
   * @param metaParam 参数对象对应的MetaObject
   * @param metaResult 主键结果集对应的MetaObject
   */
  private void handleMultipleProperties(String[] keyProperties,
      MetaObject metaParam, MetaObject metaResult) {
    //1.获取 selectKey 节点的 keyColumn 属性，即数据库主键表头数组
    String[] keyColumns = keyStatement.getKeyColumns();

    //2.如果没有指定数据库主键表头
    if (keyColumns == null || keyColumns.length == 0) {
      // no key columns specified, just use the property names
      //3.遍历 keyProperties 集合，设置主键
      for (String keyProperty : keyProperties) {
        setValue(metaParam, keyProperty, metaResult.getValue(keyProperty));
      }
    } else {
      //2.如果指定了数据库主键表头
      //3.如果 keyColumns 和 keyProperties 不匹配抛出异常
      if (keyColumns.length != keyProperties.length) {
        throw new ExecutorException("If SelectKey has key columns, the number must match the number of key properties.");
      }
      //3.遍历 keyProperties 集合，设置主键
      for (int i = 0; i < keyProperties.length; i++) {
        setValue(metaParam, keyProperties[i], metaResult.getValue(keyColumns[i]));
      }
    }
  }

  /**
   * 将 value 设置到 metaParam 中，将单个主键属性设置到参数对象
   * @param metaParam 参数对象对应的MetaObject
   * @param property 主键属性名
   * @param value 主键值
   */
  private void setValue(MetaObject metaParam, String property, Object value) {
    if (metaParam.hasSetter(property)) {
      metaParam.setValue(property, value);
    } else {
      throw new ExecutorException("No setter found for the keyProperty '" + property + "' in " + metaParam.getOriginalObject().getClass().getName() + ".");
    }
  }
}
