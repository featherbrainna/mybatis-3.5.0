/**
 *    Copyright 2009-2015 the original author or authors.
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
import org.apache.ibatis.mapping.MappedStatement;

import java.sql.Statement;

/**
 * 主键生成器接口。是返回数据库生成的自增主键值
 *
 * KeyGenerator 在获取到主键后，会设置回 parameter 参数的对应属性
 * processBefore和processAfter方法按流程都会执行，但通过配置可以将方法执行内容置空。且 processAfter 只会在写操作执行。
 * @author Clinton Begin
 */
public interface KeyGenerator {

  /**
   * 在执行insert之前执行
   * 具体来说是在 {@link org.apache.ibatis.executor.statement.BaseStatementHandler} 的构造器中调用执行
   * @param executor
   * @param ms
   * @param stmt
   * @param parameter
   */
  void processBefore(Executor executor, MappedStatement ms, Statement stmt, Object parameter);

  /**
   * 在执行insert之后执行
   * 具体来说是在 {@link org.apache.ibatis.executor.statement.PreparedStatementHandler#update(Statement)} 中最后调用执行
   * @param executor
   * @param ms
   * @param stmt
   * @param parameter
   */
  void processAfter(Executor executor, MappedStatement ms, Statement stmt, Object parameter);

}
