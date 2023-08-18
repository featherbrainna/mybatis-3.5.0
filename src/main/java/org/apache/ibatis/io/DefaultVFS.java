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
package org.apache.ibatis.io;

import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

/**
 * 默认的VFS实现
 * A default implementation of {@link VFS} that works for most application servers.
 *
 * @author Ben Gunter
 */
public class DefaultVFS extends VFS {
  private static final Log log = LogFactory.getLog(DefaultVFS.class);

  /** The magic header that indicates a JAR (ZIP) file. */
  private static final byte[] JAR_MAGIC = { 'P', 'K', 3, 4 };

  @Override
  public boolean isValid() {
    return true;
  }

  /**
   * 递归的列出指定url下的(指定path下的)所有的资源集合（以资源名为值）
   * 1.jar包的url时，返回指定jar包中的指定path下的所有资源路径集合（路径以包名开头），集合中不包括包
   * 2.普通文件夹的url时，返回指定文件夹中指定path下的所有资源路径集合（路径以包名开头），集合中包括包
   * 3.普通文件的url时，返回空集合
   * 【特别的】：文件系统基于类加载器，即只能读取类路径上的文件
   * @param url The URL that identifies the resource to list.
   * @param path
   * @return
   * @throws IOException
   */
  @Override
  public List<String> list(URL url, String path) throws IOException {
    InputStream is = null;
    try {
      //资源集合，最终返回
      List<String> resources = new ArrayList<>();

      // First, try to find the URL of a JAR file containing the requested resource. If a JAR
      // file is found, then we'll list child resources by reading the JAR.
      //1.如果url指向的是 Jar Resource，则返回该 Jar Resource，否则返回null。这里的jarUrl会减去.jar后面的路径
      URL jarUrl = findJarForResource(url);
      //2.url是jar包
      if (jarUrl != null) {
        //2.1读取jar包
        is = jarUrl.openStream();
        if (log.isDebugEnabled()) {
          log.debug("Listing " + url);
        }
        //2.2递归遍历Jar包中的资源，并返回以path开头的资源列表
        resources = listResources(new JarInputStream(is), path);
      }
      else {
        //3.url不是jar包
        List<String> children = new ArrayList<>();
        try {
          // TODO 不理解url已经不是jar包了，为什么还要判断isJar(url)
          //3.1url不是jar包，但是jar流。在有些情况下 URL 引用的资源实际上并不是一个 jar，但是打开一个URL的连接返回的流对象是一个JAR的流
          if (isJar(url)) {
            // Some versions of JBoss VFS might give a JAR stream even if the resource
            // referenced by the URL isn't actually a JAR
            is = url.openStream();
            try (JarInputStream jarInput = new JarInputStream(is)) {
              if (log.isDebugEnabled()) {
                log.debug("Listing " + url);
              }
              //遍历添加资源名到children集合
              for (JarEntry entry; (entry = jarInput.getNextJarEntry()) != null; ) {
                if (log.isDebugEnabled()) {
                  log.debug("Jar entry: " + entry.getName());
                }
                children.add(entry.getName());
              }
            }
          }
          else {
            /*
             * 3.2url不是jar包，也不是jar流
             * Some servlet containers allow reading from directory resources like a
             * text file, listing the child resources one per line. However, there is no
             * way to differentiate between directory and file resources just by reading
             * them. To work around that, as each line is read, try to look it up via
             * the class loader as a child of the current resource. If any line fails
             * then we assume the current resource is not a directory.
             */
            //3.3基于io流读取url下的文件夹的中文件名到lines集合中，文件名 不包含路径
            is = url.openStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            List<String> lines = new ArrayList<>();
            for (String line; (line = reader.readLine()) != null;) {
              if (log.isDebugEnabled()) {
                log.debug("Reader entry: " + line);
              }
              lines.add(line);
              //如果url是文件，则清空lines集合，返回空的resources集合
              if (getResources(path + "/" + line).isEmpty()) {
                lines.clear();
                break;
              }
            }

            //3.4lines集合不为空，将其添加到children集合
            if (!lines.isEmpty()) {
              if (log.isDebugEnabled()) {
                log.debug("Listing " + url);
              }
              children.addAll(lines);
            }
          }
        } catch (FileNotFoundException e) {
          /*
           * For file URLs the openStream() call might fail, depending on the servlet
           * container, because directories can't be opened for reading. If that happens,
           * then list the directory directly instead.
           */
          if ("file".equals(url.getProtocol())) {
            File file = new File(url.getFile());
            if (log.isDebugEnabled()) {
                log.debug("Listing directory " + file.getAbsolutePath());
            }
            if (file.isDirectory()) {
              if (log.isDebugEnabled()) {
                  log.debug("Listing " + url);
              }
              children = Arrays.asList(file.list());
            }
          }
          else {
            // No idea where the exception came from so rethrow it
            throw e;
          }
        }

        // The URL prefix to use when recursively listing child resources
        //3.5将url字符串作为前缀，添加"/"到前缀
        String prefix = url.toExternalForm();
        if (!prefix.endsWith("/")) {
          prefix = prefix + "/";
        }

        // Iterate over immediate children, adding files and recursing into directories
        //3.6递归遍历子路径，通过递归调用list(childUrl, resourcePath)
        for (String child : children) {
          //通过path和文件名构建资源路径（资源名）
          String resourcePath = path + "/" + child;
          //添加资源路径（资源路径包括子文件和子文件夹）
          resources.add(resourcePath);
          //获取子文件或子文件夹的URL
          URL childUrl = new URL(prefix + child);
          //递归遍历子路径，并将结果添加到 resources 中
          resources.addAll(list(childUrl, resourcePath));
        }
      }

      return resources;
    } finally {
      //关闭流
      if (is != null) {
        try {
          is.close();
        } catch (Exception e) {
          // Ignore
        }
      }
    }
  }

  /**
   * 读取jar包输入流返回资源列表
   * List the names of the entries in the given {@link JarInputStream} that begin with the
   * specified {@code path}. Entries will match with or without a leading slash.
   *
   * @param jar The JAR input stream
   * @param path The leading path to match
   * @return The names of all the matching entries
   * @throws IOException If I/O errors occur
   */
  protected List<String> listResources(JarInputStream jar, String path) throws IOException {
    // Include the leading and trailing slash when matching names
    //1.保证头尾都是"/"
    if (!path.startsWith("/")) {
      path = "/" + path;
    }
    if (!path.endsWith("/")) {
      path = path + "/";
    }

    // Iterate over the entries and collect those that begin with the requested path
    //2.递归的（JarInputStream实现递归）遍历整个Jar包，将以path开头的资源记录到resources集合中并返回
    List<String> resources = new ArrayList<>();
    for (JarEntry entry; (entry = jar.getNextJarEntry()) != null;) {
      //3.entry不是目录，若是目录会跳过不处理
      if (!entry.isDirectory()) {
        // Add leading slash if it's missing
        //3.1获取资源名字
        StringBuilder name = new StringBuilder(entry.getName());
        //3.2如果name不是以"/"开头，则为其添加"/"
        if (name.charAt(0) != '/') {
          name.insert(0, '/');
        }

        // Check file name
        //3.3检测name是否以path开头
        if (name.indexOf(path) == 0) {
          if (log.isDebugEnabled()) {
            log.debug("Found resource: " + name);
          }
          // Trim leading slash
          //3.4将资源名添加到资源集合
          resources.add(name.substring(1));
        }
      }
    }
    return resources;
  }

  /**
   * Attempts to deconstruct the given URL to find a JAR file containing the resource referenced
   * by the URL. That is, assuming the URL references a JAR entry, this method will return a URL
   * that references the JAR file containing the entry. If the JAR cannot be located, then this
   * method returns null.
   *
   * @param url The URL of the JAR entry.
   * @return The URL of the JAR file, if one is found. Null if not.
   * @throws MalformedURLException
   */
  protected URL findJarForResource(URL url) throws MalformedURLException {
    if (log.isDebugEnabled()) {
      log.debug("Find JAR URL: " + url);
    }

    // If the file part of the URL is itself a URL, then that URL probably points to the JAR
    // 这段代码看起来比较神奇，虽然看起来没有 break 的条件，但是是通过 MalformedURLException 异常进行
    // 正如上面英文注释，如果 URL 的文件部分本身就是 URL ，那么该 URL 可能指向 JAR
    //1.获取文件部分url（只有jar协议时生效）
    try {
      for (;;) {
        //原url去除第一个协议"jar:"后创建URL对象，若协议为file:则此过程抛出异常
        url = new URL(url.getFile());
        if (log.isDebugEnabled()) {
          log.debug("Inner URL: " + url);
        }
      }
    } catch (MalformedURLException e) {
      // This will happen at some point and serves as a break in the loop
    }

    // Look for the .jar extension and chop off everything after that
    //2.将url转换为字符串
    StringBuilder jarUrl = new StringBuilder(url.toExternalForm());
    //3.获取以".jar"结尾的下标
    int index = jarUrl.lastIndexOf(".jar");
    //4.判断是指向jar文件的url
    if (index >= 0) {
      //4.1是.jar结尾文件
      jarUrl.setLength(index + 4);
      if (log.isDebugEnabled()) {
        log.debug("Extracted JAR URL: " + jarUrl);
      }
    }
    else {
      //4.1不是.jar结尾文件，返回null
      if (log.isDebugEnabled()) {
        log.debug("Not a JAR: " + jarUrl);
      }
      return null;
    }

    // Try to open and test it
    //5.是.jar结尾文件继续处理
    try {
      //6.获取jar文件的url
      URL testUrl = new URL(jarUrl.toString());
      //7.判断是否为合法的jar文件，即jar文件是否损坏，合法则返回url
      if (isJar(testUrl)) {
        return testUrl;
      }
      else {
        //7.不合法
        // WebLogic fix: check if the URL's file exists in the filesystem.
        if (log.isDebugEnabled()) {
          log.debug("Not a JAR: " + jarUrl);
        }
        // 获得文件
        jarUrl.replace(0, jarUrl.length(), testUrl.getFile());
        File file = new File(jarUrl.toString());

        // File name might be URL-encoded
        // 处理路径编码问题
        if (!file.exists()) {
          try {
            file = new File(URLEncoder.encode(jarUrl.toString(), "UTF-8"));
          } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("Unsupported encoding?  UTF-8?  That's unpossible.");
          }
        }

        // 判断文件存在
        if (file.exists()) {
          if (log.isDebugEnabled()) {
            log.debug("Trying real file: " + file.getAbsolutePath());
          }
          testUrl = file.toURI().toURL();
          // 判断是否为 Jar 文件
          if (isJar(testUrl)) {
            return testUrl;
          }
        }
      }
    } catch (MalformedURLException e) {
      log.warn("Invalid JAR URL: " + jarUrl);
    }

    if (log.isDebugEnabled()) {
      log.debug("Not a JAR: " + jarUrl);
    }
    return null;
  }

  /**
   * Converts a Java package name to a path that can be looked up with a call to
   * {@link ClassLoader#getResources(String)}.
   *
   * @param packageName The Java package name to convert to a path
   */
  protected String getPackagePath(String packageName) {
    return packageName == null ? null : packageName.replace('.', '/');
  }

  /**
   * Returns true if the resource located at the given URL is a JAR file.
   *
   * @param url The URL of the resource to test.
   */
  protected boolean isJar(URL url) {
    return isJar(url, new byte[JAR_MAGIC.length]);
  }

  /**
   * Returns true if the resource located at the given URL is a JAR file.
   *
   * @param url The URL of the resource to test.
   * @param buffer A buffer into which the first few bytes of the resource are read. The buffer
   *            must be at least the size of {@link #JAR_MAGIC}. (The same buffer may be reused
   *            for multiple calls as an optimization.)
   */
  protected boolean isJar(URL url, byte[] buffer) {
    InputStream is = null;
    try {
      is = url.openStream();
      //1.读取url文件的头几个字节，校验jar文件格式
      is.read(buffer, 0, JAR_MAGIC.length);
      //2.校验成功返回true
      if (Arrays.equals(buffer, JAR_MAGIC)) {
        if (log.isDebugEnabled()) {
          log.debug("Found JAR: " + url);
        }
        return true;
      }
    } catch (Exception e) {
      // Failure to read the stream means this is not a JAR
    } finally {
      if (is != null) {
        try {
          is.close();
        } catch (Exception e) {
          // Ignore
        }
      }
    }

    return false;
  }
}
