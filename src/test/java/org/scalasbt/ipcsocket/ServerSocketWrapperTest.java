package org.scalasbt.ipcsocket;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.ProtocolFamily;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

public class ServerSocketWrapperTest {
  private interface Action {
    void apply(SocketWrapper a, SocketChannel b) throws Throwable;
  }

  private void withServerAndClient(Action action) throws Throwable {
    try {
      Path dir = Files.createTempDirectory(ServerSocketWrapperTest.class.getSimpleName());
      Path path = dir.resolve("socket");
      try {
        if (!Files.isDirectory(dir)) {
          Files.createDirectories(dir);
        }
        ServerSocketWrapper serverSocket =
            ServerSocketWrapper.newJdkUnixDomainSocket(path.toFile().getAbsolutePath());
        try {
          Method openMethod = SocketChannel.class.getMethod("open", ProtocolFamily.class);
          SocketChannel client =
              (SocketChannel) openMethod.invoke(null, ServerSocketWrapper.unixProtocolFamily());
          client.connect(
              ServerSocketWrapper.unixDomainSocketAddress(path.toFile().getAbsolutePath()));
          SocketWrapper server = serverSocket.accept();
          action.apply(server, client);
        } finally {
          serverSocket.close();
        }
      } finally {
        Files.deleteIfExists(path);
        Files.deleteIfExists(dir);
      }
    } catch (IOException | ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  private List<Byte> readAll(SocketChannel client) throws IOException {
    int res;
    List<Byte> values = new ArrayList<>();
    do {
      ByteBuffer buf = ByteBuffer.allocate(1);
      res = client.read(buf);
      if (res != -1) {
        values.add(buf.get(0));
      }
    } while (res != -1);
    return values;
  }

  private static final List<Integer> intValues;
  private static final List<Byte> byteValues;
  private static final boolean hasJavaNetUnixDomainSocketAddress;

  static {
    final List<Integer> list =
        Stream.<Integer>iterate((int) Byte.MIN_VALUE, x -> x + 1)
            .limit(1024)
            .collect(Collectors.toList());
    Collections.shuffle(list);
    intValues = Collections.unmodifiableList(list);
    byteValues =
        Collections.unmodifiableList(
            intValues.stream().map(Integer::byteValue).collect(Collectors.toList()));

    boolean hasUnixDomainSocketAddress = false;
    try {
      Class.forName("java.net.UnixDomainSocketAddress");
      hasUnixDomainSocketAddress = true;
    } catch (ClassNotFoundException e) {
    }
    hasJavaNetUnixDomainSocketAddress = hasUnixDomainSocketAddress;
  }

  @Test
  public void writeInt() throws Throwable {
    assumeTrue(hasJavaNetUnixDomainSocketAddress);
    withServerAndClient(
        (server, client) -> {
          try {
            intValues.forEach(
                x -> {
                  try {
                    server.write(x);
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                });
          } finally {
            server.close();
          }
          List<Byte> res = readAll(client);
          assertEquals(byteValues, res);
        });
  }
}
