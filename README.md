# Invoker

Zero config CLI, HTTP, and REPL interface for Clojure.

![It's magic. I ain't gotta explain shit.](its-magic.jpg)

Given `src/app.clj`:

``` clojure
(ns app)

(defn my-fn
  "My doc"
  {:invoker/http true}
  [x y & {:as opts}]
  [x y opts])
```

You can call it with the `nvk` CLI:

``` sh
$  nvk app/my-fn 1 2
$  nvk app my-fn 1 2
$  nvk app my-fn 1 2 :a 1
$  nvk app my-fn 1 2 --a 1
$  nvk app my-fn 1 2 --a=1
```

You can also serve it with `nvk http`, then call it with `curl` or by opening the address on your browser:

``` sh
$ nvk http
$ curl localhost/app/my-fn/1/2
$ curl localhost/app/my-fn/1/2?a=1
$ curl localhost/app/my-fn/1/2 -d a=1
```

You can also start a REPL, or connect to an existing one nrepl port, with `nvk repl`.

``` sh
$ invoker repl
[Rebel readline] Type :repl/help for online help info
user=>
```

## Core Ideas

Invoker is driven by two core ideas:
- it should be really easy to map a function to a CLI or HTTP call
- a `(fn [x y z & {:as opts}])` signature maps pretty well to CLI and HTTP idioms


## How to

### Call invoker from clj/bb

### Get the http request

### Make your own CLI tool
