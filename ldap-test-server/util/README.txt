A simple utility to generate encrypted password entries appropriate for userPassword attribute in LDIF files for Apache Directory Server.

This is not supposed to be terribly secure since SHA1 is no longer secure but using a more secure algorithm requires moving to Apache DS 2.x while this test server still is based on 1.x release.

To run compile GenerateSHA1PasswordEntry.java with javac and run: java -cp . GenerateSHA1PasswordEntry
Enter the password twice and the output can be put in ldif file like:

userPassword:: ...

See the available options by passing "-h" or "--help" argument.
