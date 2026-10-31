#!/usr/bin/env python3
"""PhonePal developer CLI. Run `python3 phonepal.py --help`."""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from phonepal_cli.main import main  # noqa: E402

if __name__ == "__main__":
    sys.exit(main())
