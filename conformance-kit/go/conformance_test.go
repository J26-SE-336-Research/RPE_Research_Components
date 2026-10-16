package conformance

import (
	"reflect"
	"testing"
)

const dir = "../vectors"

func eqFail(a, b *Failure) bool {
	if a == nil || b == nil {
		return a == b
	}
	return a.Code == b.Code && a.Rule == b.Rule
}

func TestContextDecode(t *testing.T) {
	s, err := Load[DecodeCase](dir, "context-decode.json")
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range s.Cases {
		f, canonical, fail := Decode(c.Input)
		if !eqFail(fail, c.Error) || !reflect.DeepEqual(f, c.Expect) || canonical != c.Canonical {
			t.Errorf("%s: got %+v %q %+v", c.Name, f, canonical, fail)
		}
	}
}

func TestContextEncode(t *testing.T) {
	s, err := Load[EncodeCase](dir, "context-encode.json")
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range s.Cases {
		if got := ToContext(c.Fields).Encode(); got != c.Expect {
			t.Errorf("%s: got %q", c.Name, got)
		}
	}
}

func TestContextTransition(t *testing.T) {
	s, err := Load[TransitionCase](dir, "context-transition.json")
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range s.Cases {
		if got := Transition(c.Parent, c.Child, c.ElapsedMs); !reflect.DeepEqual(got, c.Violations) {
			t.Errorf("%s: got %v", c.Name, got)
		}
	}
}

func TestContextChild(t *testing.T) {
	s, err := Load[ChildCase](dir, "context-child.json")
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range s.Cases {
		k, fail := Child(c.Parent, c.ElapsedMs, c.ReserveMs, c.Grant)
		if !eqFail(fail, c.Error) {
			t.Errorf("%s: error %+v", c.Name, fail)
			continue
		}
		if fail == nil && (k.TTLMillis != *c.ExpectTTL || k.RetryBudget != *c.ExpectRB || k.PID != c.Parent.IID || k.IID == c.Parent.IID) {
			t.Errorf("%s: got %+v", c.Name, k)
		}
	}
}

func TestBudgetSplit(t *testing.T) {
	s, err := Load[SplitCase](dir, "budget-split.json")
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range s.Cases {
		if got := Split(c.Mode, c.RB, c.Reserve, c.N); !reflect.DeepEqual(got, c.Expect) {
			t.Errorf("%s: got %v", c.Name, got)
		}
	}
}

func TestResult(t *testing.T) {
	s, err := Load[ResultCase](dir, "result.json")
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range s.Cases {
		f, canonical, fail := Result(c.Input)
		if !eqFail(fail, c.Error) || !reflect.DeepEqual(f, c.Expect) || canonical != c.Canonical {
			t.Errorf("%s: got %+v %q %+v", c.Name, f, canonical, fail)
		}
	}
}

func TestHop(t *testing.T) {
	s, err := Load[HopCase](dir, "hop.json")
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range s.Cases {
		cb, att, canonical, fail := Hop(c.Input)
		if !eqFail(fail, c.Error) || cb != c.ExpectCB || att != c.ExpectAtt || canonical != c.Canonical {
			t.Errorf("%s: got %q %d %q %+v", c.Name, cb, att, canonical, fail)
		}
	}
}

func TestTraceparent(t *testing.T) {
	s, err := Load[TraceparentCase](dir, "traceparent.json")
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range s.Cases {
		if got := Traceparent(c.Input); got != c.Expect {
			t.Errorf("%s: got %q", c.Name, got)
		}
	}
}
