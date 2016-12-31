// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: load_balancer.proto

package io.grpc.grpclb;

public interface ServerOrBuilder extends
    // @@protoc_insertion_point(interface_extends:grpc.lb.v1.Server)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * A resolved address for the server, serialized in network-byte-order. It may
   * either be an IPv4 or IPv6 address.
   * </pre>
   *
   * <code>optional bytes ip_address = 1;</code>
   */
  com.google.protobuf.ByteString getIpAddress();

  /**
   * <pre>
   * A resolved port number for the server.
   * </pre>
   *
   * <code>optional int32 port = 2;</code>
   */
  int getPort();

  /**
   * <pre>
   * An opaque token that is passed from the client to the server in metadata.
   * The server may expect this token to indicate that the request from the
   * client was load balanced.
   * </pre>
   *
   * <code>optional string load_balance_token = 3;</code>
   */
  java.lang.String getLoadBalanceToken();
  /**
   * <pre>
   * An opaque token that is passed from the client to the server in metadata.
   * The server may expect this token to indicate that the request from the
   * client was load balanced.
   * </pre>
   *
   * <code>optional string load_balance_token = 3;</code>
   */
  com.google.protobuf.ByteString
      getLoadBalanceTokenBytes();

  /**
   * <pre>
   * Indicates whether this particular request should be dropped by the client
   * when this server is chosen from the list.
   * </pre>
   *
   * <code>optional bool drop_request = 4;</code>
   */
  boolean getDropRequest();
}