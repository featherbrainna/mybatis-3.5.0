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
package org.apache.ibatis.builder.xml;

import org.apache.ibatis.io.Resources;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Mybatis自定义EntityResolver类。用于加载本地mybatis-3-config.dtd和mybatis-3-mapper.dtd文件
 * 由 XPathParser 对象构造时调用
 * Offline entity resolver for the MyBatis DTDs
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class XMLMapperEntityResolver implements EntityResolver {

  //指定mybatis-config.xml文件和映射文件对应的DTD的Systemid
  private static final String IBATIS_CONFIG_SYSTEM = "ibatis-3-config.dtd";
  private static final String IBATIS_MAPPER_SYSTEM = "ibatis-3-mapper.dtd";
  private static final String MYBATIS_CONFIG_SYSTEM = "mybatis-3-config.dtd";
  private static final String MYBATIS_MAPPER_SYSTEM = "mybatis-3-mapper.dtd";

  //指定mybatis-config.xml文件和映射文件对应的DTD文件的具体本地位置
  /**
   * 本地mybatis-config.dtd文件
   */
  private static final String MYBATIS_CONFIG_DTD = "org/apache/ibatis/builder/xml/mybatis-3-config.dtd";
  /**
   * 本地mybatis-mapper.dtd文件
   */
  private static final String MYBATIS_MAPPER_DTD = "org/apache/ibatis/builder/xml/mybatis-3-mapper.dtd";

  /**
   * Converts a public DTD into a local one
   *
   * @param publicId The public id that is what comes after "PUBLIC"
   * @param systemId The system id that is what comes after the public id.
   * @return The InputSource for the DTD
   *
   * @throws org.xml.sax.SAXException If anything goes wrong
   */
  @Override
  public InputSource resolveEntity(String publicId, String systemId) throws SAXException {
    try {
      if (systemId != null) {
        //查找systemId指定的DTD本地文档，并调用getInputSource()方法读取DTD本地文档
        String lowerCaseSystemId = systemId.toLowerCase(Locale.ENGLISH);
        if (lowerCaseSystemId.contains(MYBATIS_CONFIG_SYSTEM) || lowerCaseSystemId.contains(IBATIS_CONFIG_SYSTEM)) {
          //返回本地mybatis-config.dtd文件
          return getInputSource(MYBATIS_CONFIG_DTD, publicId, systemId);
        } else if (lowerCaseSystemId.contains(MYBATIS_MAPPER_SYSTEM) || lowerCaseSystemId.contains(IBATIS_MAPPER_SYSTEM)) {
          //返回本地mybatis-mapper.dtd文件
          return getInputSource(MYBATIS_MAPPER_DTD, publicId, systemId);
        }
      }
      return null;
    } catch (Exception e) {
      throw new SAXException(e.toString());
    }
  }

  /**
   * 负责读取DTD本地文档并形成InputSource对象
   * @param path DTD文档本地路径
   * @param publicId
   * @param systemId
   * @return InputSource对象
   */
  private InputSource getInputSource(String path, String publicId, String systemId) {
    InputSource source = null;
    if (path != null) {
      try {
        //创建InputSource对象
        InputStream in = Resources.getResourceAsStream(path);
        source = new InputSource(in);
        //设置publicId、systemId属性
        source.setPublicId(publicId);
        source.setSystemId(systemId);
      } catch (IOException e) {
        // ignore, null is ok
      }
    }
    return source;
  }

}
