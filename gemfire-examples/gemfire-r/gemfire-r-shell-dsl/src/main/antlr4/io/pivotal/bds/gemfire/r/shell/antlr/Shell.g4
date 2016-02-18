grammar Shell;

cmd: query | execute | svm | gp | predict | print | ls | rm | evaluate ;
query: queryVar EQUALS QUERY LPAREN queryString RPAREN ;
execute: EXECUTE LPAREN queryVar (COMMA queryArg)* RPAREN ;
svm: modelVar EQUALS SVM LPAREN QUERY EQUALS queryVar (COMMA KERNEL EQUALS kernels)? (COMMA CP EQUALS cpVar)? (COMMA CN EQUALS cnVar)? (COMMA K EQUALS kVar)? (COMMA QUERYARGS EQUALS queryArgs)? RPAREN ;
gp: modelVar EQUALS GP LPAREN QUERY EQUALS queryVar (COMMA LAMBDA EQUALS lambdaVar)? (COMMA QUERYARGS EQUALS queryArgs)? RPAREN ;
predict: PREDICT LPAREN modelVar COMMA queryVar (COMMA queryArg)* RPAREN ;
evaluate: evaluateVar EQUALS EVALUATE LPAREN modelVar COMMA regionVar (COMMA fieldVar)+ RPAREN ;
print: PRINT var ;
ls: LS ;
rm: RM var ; 

kernels: ;
queryArg: QUOTEDSTRING | DECIMAL | INTEGER ;
queryArgs: LBRACKET queryArg (COMMA queryArg)* RBRACKET ;
cpVar: NUMBER ;
cnVar: NUMBER ;
kVar: INTEGER ;
lambdaVar: NUMBER ;
var: IDENTIFIER ;
evaluateVar: IDENTIFIER ;
fieldVar: IDENTIFIER ;
modelVar: IDENTIFIER ;
queryVar: IDENTIFIER ;
regionVar: IDENTIFIER ;
queryString : QUOTEDSTRING ;

EVALUATE: 'evaluate';
QUERYARGS: 'queryArgs';
MODEL: 'model';
FIELDS: 'fields';
REGION: 'region';
RM: 'rm';
LS: 'ls';
PRINT: 'print';
PREDICT: 'predict';
K: 'k';
LAMBDA: 'lambda';
GP: 'gp';
SVM: 'svm';
KERNEL: 'kernel';
CP: 'cp';
CN: 'cn';
QUERY: 'query';
EXECUTE: 'execute';

LBRACKET: '[' ;
RBRACKET: ']' ;
LPAREN : '(' ;
RPAREN : ')' ;
LBRACE : '{' ;
RBRACE : '}' ;
DBLQUOTES : '"' ;
COMMA : ',' ;
EQUALS : '=' ;
IDENTIFIER : [a-zA-Z][a-zA-Z0-9_.]* ;
INTEGER : [1-9][0-9]* ;
DECIMAL : [0-9]+ '.' [0-9]+ ;
NUMBER: DECIMAL | INTEGER ;
QUOTEDSTRING : DBLQUOTES (~["])+? DBLQUOTES ;
WS :  [ \t\r\n\u000C]+ -> skip ;