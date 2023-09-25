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
package org.apache.ibatis.session;

import org.apache.ibatis.builder.xml.XMLConfigBuilder;
import org.apache.ibatis.exceptions.ExceptionFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.session.defaults.DefaultSqlSessionFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;

/**
 * SqlSessionFactory 的具体构造器，MyBatis 的初始化流程的入口类
 *
 * 提供了各种 build 的重载方法，核心的套路都是解析出 Configuration 配置对象，
 * 从而创建出 DefaultSqlSessionFactory 对象。
 * Builds {@link SqlSession} instances.
 *
 * @author Clinton Begin
 */
public class SqlSessionFactoryBuilder {

  public SqlSessionFactory build(Reader reader) {
    return build(reader, null, null);
  }

  public SqlSessionFactory build(Reader reader, String environment) {
    return build(reader, environment, null);
  }

  public SqlSessionFactory build(Reader reader, Properties properties) {
    return build(reader, null, properties);
  }

  /**
   * 构造 SqlSessionFactory 对象
   * @param reader Reader对象
   * @param environment environment环境
   * @param properties properties对象
   * @return SqlSessionFactory 对象
   */
  public SqlSessionFactory build(Reader reader, String environment, Properties properties) {
    try {
      //1.创建 XMLConfigBuilder 对象，读取mybatis-config.xml配置文件
      XMLConfigBuilder parser = new XMLConfigBuilder(reader, environment, properties);
      //2.执行 XML 解析，解析得到 Configuration 对象
      //3.调用 build(configuration) 创建 DefaultSqlSessionFactory 对象
      return build(parser.parse());
    } catch (Exception e) {
      throw ExceptionFactory.wrapException("Error building SqlSession.", e);
    } finally {
      //4.清空线程错误上下文
      ErrorContext.instance().reset();
      try {
        //4.关闭输入流
        reader.close();
      } catch (IOException e) {
        // Intentionally ignore. Prefer previous error.
      }
    }
  }

  public SqlSessionFactory build(InputStream inputStream) {
    return build(inputStream, null, null);
  }

  public SqlSessionFactory build(InputStream inputStream, String environment) {
    return build(inputStream, environment, null);
  }

  public SqlSessionFactory build(InputStream inputStream, Properties properties) {
    return build(inputStream, null, properties);
  }

  public SqlSessionFactory build(InputStream inputStream, String environment, Properties properties) {
    try {
      //1.创建 XMLConfigBuilder 对象，读取mybatis-config.xml配置文件
      XMLConfigBuilder parser = new XMLConfigBuilder(inputStream, environment, properties);
      //2.执行 XML 解析，解析得到 Configuration 对象
      //3.调用 build(Configuration) 创建 DefaultSqlSessionFactory 对象
      return build(parser.parse());
    } catch (Exception e) {
      throw ExceptionFactory.wrapException("Error building SqlSession.", e);
    } finally {
      ErrorContext.instance().reset();
      try {
        inputStream.close();
      } catch (IOException e) {
        // Intentionally ignore. Prefer previous error.
      }
    }
  }

  /**
   * 创建 DefaultSqlSessionFactory 返回
   * 所有 build 方法底层最终调用方法
   * @param config Configuration全局mybatis配置对象
   * @return SqlSessionFactory对象
   */
  public SqlSessionFactory build(Configuration config) {
    return new DefaultSqlSessionFactory(config);
  }

}
