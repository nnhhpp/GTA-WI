# /// script
# requires-python = ">=3.9"
# dependencies = []
# ///
"""Fails the build if the final (ProGuard-ed) jar calls Java SE APIs that CLDC 1.1 / MIDP 2.0 phones don't have.
On a phone such a call dies at run time with 'java.lang.Error' + a number (the N95 said 'Error 136').
  check_cldc.py <jar> <javap>"""
import sys, os, re, zipfile, subprocess, tempfile
jar, javap = sys.argv[1], sys.argv[2]
CLDC = {'java/io': 'ByteArrayInputStream ByteArrayOutputStream DataInput DataInputStream DataOutput DataOutputStream EOFException InputStream '
                   'InputStreamReader InterruptedIOException IOException OutputStream OutputStreamWriter PrintStream Reader '
                   'UnsupportedEncodingException UTFDataFormatException Writer',
        'java/lang': 'ArithmeticException ArrayIndexOutOfBoundsException ArrayStoreException Boolean Byte Character Class ClassCastException '
                     'ClassNotFoundException Double Error Exception Float IllegalAccessException IllegalArgumentException '
                     'IllegalMonitorStateException IllegalThreadStateException IndexOutOfBoundsException InstantiationException Integer '
                     'InterruptedException Long Math NegativeArraySizeException NoClassDefFoundError NullPointerException NumberFormatException '
                     'Object OutOfMemoryError Runnable Runtime RuntimeException SecurityException Short String StringBuffer '
                     'StringIndexOutOfBoundsException System Thread Throwable VirtualMachineError',
        'java/lang/ref': 'Reference WeakReference',
        'java/util': 'Calendar Date EmptyStackException Enumeration Hashtable NoSuchElementException Random Stack Timer TimerTask TimeZone Vector'}
OK = set(p + '/' + c for p, cs in CLDC.items() for c in cs.split())
# members that exist in Java SE but not in CLDC 1.1
BAD = [r'java/lang/(Integer|Short|Byte|Long|Float|Double|Boolean|Character)\.valueOf:\([ISBJFDZC]\)',
       r'java/lang/Math\.(round|atan|atan2|pow|exp|log|log10|random|asin|acos|cbrt|hypot|signum)',
       r'java/lang/String\.(split|contains|isEmpty|format|matches|replaceAll|replaceFirst|join)',
       r'java/lang/String\.replace:\(Ljava/lang/CharSequence', r'java/lang/StringBuffer\.append:\(Ljava/lang/CharSequence',
       r'java/util/Vector\.(add|get|set|remove|iterator|addAll|clear|isEmpty|toArray):', r'java/util/Hashtable\.(keySet|entrySet|values):',
       r'java/util/Random\.(nextBoolean|nextGaussian|nextBytes)', r'java/lang/Class\.getSimpleName', r'java/lang/Object\.clone',
       r'java/lang/Thread\.(getId|setName|getName)', r'java/lang/System\.(nanoTime|arraycopy:\(Ljava/lang/Object;ILjava/lang/Object;II\)V_never)']
tmp = tempfile.mkdtemp(); z = zipfile.ZipFile(jar); names = [n for n in z.namelist() if n.endswith('.class')]
z.extractall(tmp, names)
out = subprocess.run([javap, '-c', '-p', '-cp', tmp] + [n[:-6].replace('/', '.') for n in names], capture_output=True, text=True).stdout
refs = set(re.findall(r'// (?:Method|InterfaceMethod|Field|class) ((?:java|javax)/[^ \n]+)', out))
bad = []
for r in sorted(refs):
    cls = r.split('.')[0].split(':')[0]
    if cls.startswith('java/') and cls not in OK: bad.append('class not in CLDC 1.1: ' + r)
    elif any(re.search(b, r) for b in BAD): bad.append('member not in CLDC 1.1: ' + r)
if bad:
    print('CLDC CHECK FAILED - these would crash on the phone:'); print('\n'.join('  ' + b for b in bad)); sys.exit(1)
print('CLDC check ok (%d java/javax references)' % len(refs))
