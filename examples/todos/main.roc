app [main] { pf: platform "../../platform/main.roc" }

import pf.Engine
import Todos

main : Engine.Program
main = Todos.program
