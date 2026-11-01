// Command vitalrix: `vitalrix check` sends one test log so you know the key and URL work.
package main

import (
	"context"
	"fmt"
	"os"
	"time"

	vitalrix "github.com/avinashManiTripathi/vitalrix-sdks/go"
)

func main() {
	if len(os.Args) < 2 || os.Args[1] != "check" {
		fmt.Fprintln(os.Stderr, "usage: vitalrix check")
		os.Exit(2)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	msg, err := vitalrix.Check(ctx)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	fmt.Println(msg)
}
