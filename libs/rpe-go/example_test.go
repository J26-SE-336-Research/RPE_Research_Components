package rpe

import (
	"fmt"
	"time"
)

func ExampleNewChildContext() {
	root, err := NewRootContext("orders.create", 1000, 3, time.Now())
	if err != nil {
		panic(err)
	}
	ledger, err := NewInvocationLedger(root)
	if err != nil {
		panic(err)
	}
	child, receipt, err := NewChildContext(root, ledger, 1, root.DeadlineUnixMillis)
	if err != nil {
		panic(err)
	}
	err = ValidateChildTransition(root, child, receipt)
	fmt.Println(err == nil, child.RequestID == root.RequestID, ledger.Snapshot().Unallocated)
	// Output: true true 2
}
