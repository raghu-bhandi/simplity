/*
 * Copyright (c) 2016 simplity.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.simplity.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.simplity.kernel.ApplicationError;

import org.simplity.kernel.file.FileManager;
import org.simplity.service.ServiceProtocol;

/**
 * servlet to be used to upload/download
 *
 * @author simplity.org
 */
public class Stream extends HttpServlet {
	private static final Logger logger = LoggerFactory.getLogger(Stream.class);

  private static final String DOWNLOAD = "download=";
  /** */
  private static final long serialVersionUID = 1L;

  /** uploading a file, as well as discarding a file that was uploaded earlier. */
  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException, IOException {
    try {
      /*
       * this put request could be to discard a file that was uploaded
       * earlier
       */
      String serviceName = req.getHeader(ServiceProtocol.SERVICE_NAME);
      if (ServiceProtocol.SERVICE_DELETE_FILE.equals(serviceName)) {
        String token = req.getHeader(ServiceProtocol.HEADER_FILE_TOKEN);

        logger.info("Received a request to discard temp file token " + token);

        FileManager.deleteTempFile(token);
      } else {

        logger.info("Going to upload file ");

        InputStream inStream = req.getInputStream();
        try {
          File file = FileManager.createTempFile(inStream);
          /*
           * return the file key/token back to client. Client has to
           * use this key/token to refer to this media in a service
           * call later
           */
          if (file != null) {
            resp.setHeader(ServiceProtocol.HEADER_FILE_TOKEN, file.getName());
          }
        } finally {
          inStream.close();
        }
      }
    } catch (Exception e) {

      logger.error("Error while trying to upload a file.", e);

      throw new ApplicationError(e, "Error while trying to upload a file.");
    }
  }

  /**
   * get is used to download an attachment. syntax is just ?<token> where token is the file-token
   * for this. file-token would have been delivered to the client as part of a service call.
   */
  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException, IOException {
    String token = req.getQueryString();
    /*
     * syntax for asking for download is ?download=key, otherwise it is just
     * ?key
     */
    if (token == null) {

      logger.info("No file/token specified for file download request");

      resp.setStatus(404);
      return;
    }
    boolean toDownload = false;
    if (token.indexOf(DOWNLOAD) == 0) {

      logger.info("Received a download request for token " + token);

      toDownload = true;
      token = token.substring(DOWNLOAD.length());
    } else {

      logger.info("Received request to stream token " + token);
    }
    /*
     * do we have a file for this token?
     */

    File file = FileManager.getTempFile(token);
    if (file != null) {
      this.streamFile(resp, file, toDownload, null);
      return;
    }

    logger.info("No file available for token " + token);

    resp.setStatus(404);
  }

  /**
   * @param resp
   * @param file
   * @param toDownload
   * @param fileName
   */
  private void streamFile(
      HttpServletResponse resp, File file, boolean toDownload, String fileName) {
    InputStream in = null;
    OutputStream out = null;
    try {
      in = new FileInputStream(file);
      out = resp.getOutputStream();
      FileManager.copyOut(in, out);
      if (toDownload) {
        /*
         * we do not know the file name or mime type
         */
        String hdr = "attachment; fileName=\"";
        if (fileName == null) {
          hdr += file.getName();
        } else {
          hdr += fileName;
        }
        hdr += '"';
        resp.setHeader("Content-Disposition", hdr.toString());
      }
    } catch (Exception e) {

      logger.error("Error while copying file to response", e);

      resp.setStatus(404);
    } finally {
      if (out != null) {
        try {
          out.close();
        } catch (Exception ignore) {
          //
        }
      }
      if (in != null) {
        try {
          in.close();
        } catch (Exception ignore) {
          //
        }
      }
    }
  }
}
