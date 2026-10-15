// difffuzz writes a differential corpus: deterministic random mutations of valid RPE headers together with the
// verdict of the reference implementation (canonical re-encoding, or the error code and rule). The rpe-java test
// suite replays the corpus and must reach the same verdict for every input.
//
//	go run ./cmd/difffuzz -n 5000 -seed 1 ../generated/differential.json
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"math/rand"
	"os"
	"path/filepath"
	"strings"

	conformance "github.com/J26-SE-336-Research/rpe/conformance-kit/go"
)

type entry struct {
	Header    string `json:"header"` // context | result | hop
	Input     string `json:"input"`
	Canonical string `json:"canonical,omitempty"`
	Code      string `json:"code,omitempty"`
	Rule      string `json:"rule,omitempty"`
}

var seeds = map[string][]string{
	"context": {
		`v=1, rid="4bf92f3577b34da6a3ce929d0e0e4736", iid="00f067aa0ba902b7", pid="0000000000000000", ttl=287, bud=300, rb=3, cs=none, pe="e42", rt="POST /api/orders"`,
		`v=1, rid="0af7651916cd43dd8448eb211c80319c", iid="b7ad6b7169203331", pid="00f067aa0ba902b7", ttl=12, bud=2000, rb=0, cs=expired, rt="GET /a\"b", zz="x", zy=7`,
	},
	"result": {`v=1, out=partial, rsn=deadline, term=?1, bp=2, bpu=78, rbu=1, pv="e42", late=?0, kids="inv:ok:80;loy:expired:90"`},
	"hop":    {`cb="c-7f3a", att=2`},
}

const alphabet = `abcdefghijklmnopqrstuvwxyzABCDEF0123456789 ,;="\?-*.:/_` + "\t"

func mutate(r *rand.Rand, s string) string {
	b := []byte(s)
	for n := 1 + r.Intn(3); n > 0; n-- {
		if len(b) == 0 {
			b = append(b, alphabet[r.Intn(len(alphabet))])
			continue
		}
		i := r.Intn(len(b))
		switch r.Intn(6) {
		case 0: // delete
			b = append(b[:i], b[i+1:]...)
		case 1: // insert
			b = append(b[:i], append([]byte{alphabet[r.Intn(len(alphabet))]}, b[i:]...)...)
		case 2: // replace
			b[i] = alphabet[r.Intn(len(alphabet))]
		case 3: // truncate
			b = b[:i]
		case 4: // duplicate a member
			parts := strings.Split(string(b), ", ")
			b = []byte(string(b) + ", " + parts[r.Intn(len(parts))])
		case 5: // swap two members
			parts := strings.Split(string(b), ", ")
			x, y := r.Intn(len(parts)), r.Intn(len(parts))
			parts[x], parts[y] = parts[y], parts[x]
			b = []byte(strings.Join(parts, ", "))
		}
	}
	return string(b)
}

func verdict(header, in string) entry {
	e := entry{Header: header, Input: in}
	var fail *conformance.Failure
	switch header {
	case "context":
		_, e.Canonical, fail = conformance.Decode(in)
	case "result":
		_, e.Canonical, fail = conformance.Result(in)
	case "hop":
		_, _, e.Canonical, fail = conformance.Hop(in)
	}
	if fail != nil {
		e.Canonical, e.Code, e.Rule = "", fail.Code, fail.Rule
	}
	return e
}

func main() {
	n := flag.Int("n", 5000, "number of mutated inputs")
	seed := flag.Int64("seed", 1, "random seed")
	flag.Parse()
	out := "../generated/differential.json"
	if flag.NArg() > 0 {
		out = flag.Arg(0)
	}
	r := rand.New(rand.NewSource(*seed))
	headers := []string{"context", "context", "context", "result", "hop"}
	var corpus []entry
	valid := 0
	for len(corpus) < *n {
		h := headers[r.Intn(len(headers))]
		s := seeds[h][r.Intn(len(seeds[h]))]
		in := mutate(r, s)
		if strings.TrimSpace(in) == "" && h != "context" {
			continue // an empty hop/result header means "absent", which is not a codec question
		}
		e := verdict(h, in)
		if e.Code == "" {
			valid++
		}
		corpus = append(corpus, e)
	}
	_ = os.MkdirAll(filepath.Dir(out), 0o755)
	b, _ := json.Marshal(corpus)
	if err := os.WriteFile(out, b, 0o644); err != nil {
		panic(err)
	}
	fmt.Printf("%s: %d inputs (%d still valid)\n", out, len(corpus), valid)
}
