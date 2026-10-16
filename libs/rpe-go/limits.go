package rpe

const (
	SupportedProtocolVersion    int64 = 1
	RequestIDLength                   = 32
	InvocationIDLength                = 16
	RootParentID                      = "0000000000000000"
	MinDeadlineUnixMillis       int64 = 1
	MaxDeadlineUnixMillis       int64 = 253402300799999
	MinRequestDeadlineMillis    int64 = 1
	MaxRequestDeadlineMillis    int64 = 600000
	MinRetryBudget              int64 = 0
	MaxRetryBudget              int64 = 255
	MinBackpressureLevel        int64 = 0
	MaxBackpressureLevel        int64 = 3
	MinRequestRouteBytes              = 1
	MaxRequestRouteBytes              = 128
	MinPolicyVersionBytes             = 1
	MaxPolicyVersionBytes             = 64
	MaxHeaderValueBytes               = 256
	MaxProjectHeaderBytes             = 4096
	MinRequestMethodBytes             = 1
	MaxRequestMethodBytes             = 32
	MaxTopologyTrailBytes             = 1400
	MaxTopologyRecords                = 16
	MinTopologyRegistryVersion  int64 = 1
	MaxTopologyRegistryVersion  int64 = 2147483647
	MinTopologyServiceID        int64 = 0
	MaxTopologyServiceID        int64 = 65535
	TopologyActiveElapsedMicros int64 = -1
	MaxTopologyElapsedMicros    int64 = 999999999999999
)
