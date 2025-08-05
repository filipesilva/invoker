# Invoker

Zero config CLI, HTTP, and REPL interface for Clojure.

![It's magic. I ain't gotta explain shit.](its-magic.jpg)

If you have this namespace:

``` clojure
(ns app.thing)

(defn foo
  "A simple function."
  [id {:keys [a b]}]
  [id a b])
```

You can call it with the `invoker` CLI:

``` sh
$ invoker app.thing/foo 1 --a 2 --b 3
[1 2 3]
$ invoker app thing foo 1 --a 2 --b 3
[1 2 3]
```

You can also serve it with `invoker http`, then call it with `curl` or by opening the address on your browser:

``` sh
$ curl http://localhost/app/thing/foo/1?a=2&b=3
[1 2 3]
```

You can also start a REPL, or connect to an existing one nrepl port, with `invoker repl`.

``` sh
$ invoker repl
[Rebel readline] Type :repl/help for online help info
user=>
```

## Core Ideas

Invoker is driven by two core ideas:
- it should be really easy to map a function to a CLI or HTTP call
- a `(fn [x y z & {:as opts}])` signature maps pretty well to CLI and HTTP idioms

## OpenAPI

The http server will also exposes OpenAPI specs, and serves as a OpenAPI client.

``` sh
$ invoker http://localhost:80
url           | operation-id  | description
--------------|---------------|----------------------
app/thing/foo | app.thing/foo | ([id {:keys [a b]}])
              |               |   A simple function.

$ invoker http://localhost:80/app/thing/foo 1 --a 2 --b 3
[1 2 3]
```

You can use `operation-id` with `invoker/openapi` clojure client to explore and communicate between invoker processes:

``` clojure
(invoker/openapi "http://localhost:80")
;; => ...
(invoker/openapi "http://localhost:80" :app.thing/foo 1 {:a 2, :b 3})
;; => [1 2 3]
```

## How to

### Call invoker from clj/bb

### Get the http request

### Make your own CLI tool
